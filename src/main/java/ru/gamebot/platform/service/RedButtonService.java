package ru.gamebot.platform.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.model.AppSetting;
import ru.gamebot.platform.domain.model.DailyBonus;
import ru.gamebot.platform.domain.model.RedButtonSchedule;
import ru.gamebot.platform.domain.repository.AppSettingRepository;
import ru.gamebot.platform.domain.repository.DailyBonusRepository;
import ru.gamebot.platform.domain.repository.RedButtonScheduleRepository;
import ru.gamebot.platform.domain.repository.UserBonusRepository;
import ru.gamebot.platform.event.RedButtonEvent;

/**
 * «Красная кнопка» (21.10.2026, этап 3 ТЗ «Курс дня»): редкий крупный бонус к курсу вывода. По умолчанию ВЫКЛЮЧЕНА (/redbutton on).
 * Раз в неделю планируются дни и случайное время (окно часов UTC), перед первой кнопкой недели выходит тизер (через обычное одобрение),
 * сама кнопка публикуется БЕЗ одобрения в назначенное время (иначе теряется случайность): за warnMin минут админы получают предупреждение с кнопкой «Отменить».
 * После окна нажатия пост редактируется (кнопка убирается, добавляется итог). Бонус игрока живёт bcr.validhours часов с момента нажатия.
 * Все настройки - в app_settings (bcr.*), время в UTC. Telegram-действия делает бот по событиям RedButtonEvent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedButtonService {

    private static final int DEFAULT_PER_WEEK = 2;
    private static final int DEFAULT_START_HOUR_UTC = 7;
    private static final int DEFAULT_END_HOUR_UTC = 19;
    private static final int DEFAULT_MIN_PERCENT = 30;
    private static final int DEFAULT_MAX_PERCENT = 60;
    private static final int DEFAULT_STEP_PERCENT = 10;
    private static final int DEFAULT_WINDOW_MIN = 60;
    private static final long DEFAULT_BUDGET_RUB = 70;
    private static final int DEFAULT_TEASER_HOURS = 24;
    private static final int DEFAULT_WARN_MIN = 10;
    /** Кнопка, которую не удалось выпустить вовремя (бот был недоступен), позже этого срока уже не публикуется: окно нажатия 60 минут, опоздавший пост вводит в заблуждение. */
    private static final int MAX_LATE_MIN = 30;
    private static final int PUBLISHING_TIMEOUT_MIN = 5;
    private static final int MAX_ATTEMPTS = 2;

    private final AppSettingRepository settingRepository;
    private final RedButtonScheduleRepository scheduleRepository;
    private final DailyBonusRepository dailyBonusRepository;
    private final UserBonusRepository userBonusRepository;
    private final ChannelContentService channelContentService;
    private final BonusCourseService bonusCourseService;
    private final ApplicationEventPublisher eventPublisher;

    public record Prepared(String text, String deepLink) { }

    // ───────────────────────── настройки ─────────────────────────

    private String get(String key) {
        return settingRepository.findById(key).map(AppSetting::getValue).orElse(null);
    }

    private void put(String key, String value) {
        AppSetting s = settingRepository.findById(key).orElseGet(AppSetting::new);
        s.setKey(key);
        s.setValue(value);
        settingRepository.save(s);
    }

    private static long parseLong(String v, long def) {
        try { return v == null ? def : Long.parseLong(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    public boolean isEnabled() { return "1".equals(get("bcr.enabled")); }

    /** Выключение отменяет ещё не вышедшие запланированные кнопки; уже вышедшая закроется как обычно. */
    public void setEnabled(boolean on) {
        put("bcr.enabled", on ? "1" : "0");
        if (!on) {
            for (RedButtonSchedule r : scheduleRepository.findByStatusInOrderByPlannedAt(List.of(RedButtonSchedule.PLANNED))) {
                r.setStatus(RedButtonSchedule.CANCELLED);
                r.setStatusAt(LocalDateTime.now());
                scheduleRepository.save(r);
            }
        }
    }

    public int perWeek() { return (int) Math.max(0, Math.min(7, parseLong(get("bcr.perweek"), DEFAULT_PER_WEEK))); }

    private int startHour() { return (int) Math.max(0, Math.min(23, parseLong(get("bcr.start"), DEFAULT_START_HOUR_UTC))); }

    private int endHour() { return (int) Math.max(1, Math.min(24, parseLong(get("bcr.end"), DEFAULT_END_HOUR_UTC))); }

    private int minPercent() { return (int) Math.max(1, parseLong(get("bcr.min"), DEFAULT_MIN_PERCENT)); }

    private int maxPercent() { return (int) Math.max(minPercent(), parseLong(get("bcr.max"), DEFAULT_MAX_PERCENT)); }

    private int stepPercent() { return (int) Math.max(1, parseLong(get("bcr.step"), DEFAULT_STEP_PERCENT)); }

    public int windowMin() { return (int) Math.max(5, Math.min(720, parseLong(get("bcr.window"), DEFAULT_WINDOW_MIN))); }

    public long budgetRub() { return Math.max(0, parseLong(get("bcr.budget"), DEFAULT_BUDGET_RUB)); }

    private int teaserHours() { return (int) Math.max(0, Math.min(168, parseLong(get("bcr.teaserhours"), DEFAULT_TEASER_HOURS))); }

    private int warnMin() { return (int) Math.max(0, Math.min(120, parseLong(get("bcr.warn"), DEFAULT_WARN_MIN))); }

    /** Команда /redbutton set <ключ> <значение>: проверяет диапазон, возвращает текст ответа админу. */
    public String setParam(String key, String value) {
        long v = parseLong(value, Long.MIN_VALUE);
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "perweek" -> {
                if (v < 0 || v > 7) yield "Кнопок в неделю: число от 0 до 7.";
                put("bcr.perweek", String.valueOf(v));
                yield "✅ Красных кнопок в неделю: " + v + " (со следующего планирования недели).";
            }
            case "start" -> {
                if (v < 0 || v > 23 || v >= endHour()) yield "Начало окна старта по UTC: число от 0 до 23, меньше конца (сейчас конец " + endHour() + ").";
                put("bcr.start", String.valueOf(v));
                yield "✅ Кнопка стартует не раньше " + String.format("%02d:00", v) + " UTC.";
            }
            case "end" -> {
                if (v < 1 || v > 24 || v <= startHour()) yield "Конец окна старта по UTC: число от 1 до 24, больше начала (сейчас начало " + startHour() + ").";
                put("bcr.end", String.valueOf(v));
                yield "✅ Кнопка стартует не позже " + String.format("%02d:00", v % 24) + " UTC.";
            }
            case "min" -> {
                if (v < 1 || v > 500) yield "Минимальный процент бонуса: число от 1 до 500.";
                put("bcr.min", String.valueOf(v));
                yield "✅ Минимальный бонус красной кнопки: +" + v + "%.";
            }
            case "max" -> {
                if (v < 1 || v > 500) yield "Максимальный процент бонуса: число от 1 до 500.";
                put("bcr.max", String.valueOf(v));
                yield "✅ Максимальный бонус красной кнопки: +" + v + "%.";
            }
            case "step" -> {
                if (v < 1 || v > 100) yield "Шаг процента: число от 1 до 100.";
                put("bcr.step", String.valueOf(v));
                yield "✅ Шаг процента: " + v + ".";
            }
            case "window" -> {
                if (v < 5 || v > 720) yield "Окно нажатия в минутах: число от 5 до 720.";
                put("bcr.window", String.valueOf(v));
                yield "✅ Кнопка живёт " + v + " минут.";
            }
            case "valid" -> {
                if (v < 1 || v > 24) yield "Срок бонуса после нажатия в часах: число от 1 до 24.";
                bonusCourseService.setRedValidHours((int) v);
                yield "✅ Бонус действует " + v + " ч с момента нажатия.";
            }
            case "budget" -> {
                if (v < 0 || v > 1000000) yield "Бюджет одной кнопки в рублях: число от 0 до 1000000.";
                put("bcr.budget", String.valueOf(v));
                yield "✅ Бюджет одной красной кнопки: " + v + " ₽.";
            }
            case "teaser" -> {
                if (v < 0 || v > 168) yield "За сколько часов до первой кнопки недели выходит тизер: число от 0 до 168 (0 - без тизера).";
                put("bcr.teaserhours", String.valueOf(v));
                yield "✅ Тизер выходит за " + v + " ч до первой кнопки недели.";
            }
            case "warn" -> {
                if (v < 0 || v > 120) yield "За сколько минут предупреждать админов: число от 0 до 120.";
                put("bcr.warn", String.valueOf(v));
                yield "✅ Админы получают предупреждение за " + v + " мин до кнопки.";
            }
            default -> "Параметры: perweek, start, end, min, max, step, window, valid, budget, teaser, warn.";
        };
    }

    /** Тексты постов хранятся в настройках и правятся без деплоя. kind: post | teaser. Подстановки в посте: {p} процент, {w} минут окна, {until} конец окна (МСК), {v} часов бонуса, {cap} потолок суммы. */
    public String setText(String kind, String text) {
        if (text == null || text.isBlank() || text.length() > 2500) return "Текст пустой или длиннее 2500 символов.";
        switch (kind.toLowerCase(Locale.ROOT)) {
            case "post" -> put("bcr.text.post", text.trim());
            case "teaser" -> put("bcr.text.teaser", text.trim());
            default -> { return "Формат: <code>/redbutton text post|teaser ТЕКСТ</code>."; }
        }
        return "✅ Текст (" + kind.toLowerCase(Locale.ROOT) + ") сохранён. Подстановки в посте: {p} {w} {until} {v} {cap}.";
    }

    // ───────────────────────── планирование и жизненный цикл ─────────────────────────

    /** Раз в минуту: планирует неделю (если включено), рассылает предупреждения/тизеры, запускает публикацию и закрывает окна. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 240_000)
    public void tick() {
        try {
            LocalDateTime now = LocalDateTime.now();
            if (isEnabled()) planWeekIfNeeded(now);
            process(now);
        } catch (Exception e) {
            log.error("[RedButton] tick failed", e);
        }
    }

    private void planWeekIfNeeded(LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        LocalDate weekStart = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        if (weekStart.toString().equals(get("bcr.planned"))) return;
        put("bcr.planned", weekStart.toString());
        planWeek(now, weekStart);
    }

    /** Выбирает дни (разные) и случайное время старта оставшейся части недели. Каждая кнопка - не раньше чем через (тизер + 1 час) от момента планирования,
     *  чтобы до первой успел выйти тизер. Включение в середине недели даёт меньше кнопок: недостающие не добираются. Возвращает число запланированных. */
    public int planWeek(LocalDateTime now, LocalDate weekStart) {
        int n = perWeek();
        if (n <= 0) return 0;
        LocalDateTime earliest = now.plusHours(teaserHours() + 1L);
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = now.toLocalDate(); !d.isAfter(weekStart.plusDays(6)); d = d.plusDays(1)) {
            if (slotLow(d, earliest).isBefore(slotHigh(d))) days.add(d);
        }
        Collections.shuffle(days);
        List<LocalDate> chosen = new ArrayList<>(days.subList(0, Math.min(n, days.size())));
        Collections.sort(chosen);
        int count = 0;
        for (LocalDate d : chosen) {
            LocalDateTime lo = slotLow(d, earliest);
            LocalDateTime hi = slotHigh(d);
            long minutes = Math.max(1, ChronoUnit.MINUTES.between(lo, hi));
            LocalDateTime at = lo.plusMinutes(ThreadLocalRandom.current().nextLong(minutes)).truncatedTo(ChronoUnit.MINUTES);
            RedButtonSchedule r = new RedButtonSchedule();
            r.setWeekStart(weekStart);
            r.setPlannedAt(at);
            r.setPercent(randomPercent());
            r.setFirstOfWeek(count == 0);
            r.setStatusAt(now);
            scheduleRepository.save(r);
            count++;
        }
        log.info("[RedButton] planned {} buttons for week {}", count, weekStart);
        return count;
    }

    private LocalDateTime slotLow(LocalDate day, LocalDateTime earliest) {
        LocalDateTime start = day.atStartOfDay().plusHours(startHour());
        return start.isBefore(earliest) ? earliest : start;
    }

    private LocalDateTime slotHigh(LocalDate day) {
        return day.atStartOfDay().plusHours(endHour()).minusMinutes(5);
    }

    private int randomPercent() {
        int steps = (maxPercent() - minPercent()) / stepPercent();
        return minPercent() + stepPercent() * ThreadLocalRandom.current().nextInt(steps + 1);
    }

    /** Ручной запуск (/redbutton now [процент]): кнопка выходит на ближайшем тике, без тизера и предупреждения. */
    public RedButtonSchedule createNow(Integer percent) {
        LocalDateTime now = LocalDateTime.now();
        RedButtonSchedule r = new RedButtonSchedule();
        r.setWeekStart(now.toLocalDate().minusDays(now.getDayOfWeek().getValue() - 1L));
        r.setPlannedAt(now.truncatedTo(ChronoUnit.MINUTES));
        r.setPercent(percent != null ? percent : randomPercent());
        r.setTeaserSent(true);
        r.setWarned(true);
        r.setStatusAt(now);
        return scheduleRepository.save(r);
    }

    private void process(LocalDateTime now) {
        List<RedButtonSchedule> rows = scheduleRepository.findByStatusInOrderByPlannedAt(
                List.of(RedButtonSchedule.PLANNED, RedButtonSchedule.PUBLISHING, RedButtonSchedule.PUBLISHED));
        for (RedButtonSchedule r : rows) {
            try {
                switch (r.getStatus()) {
                    case RedButtonSchedule.PLANNED -> processPlanned(r, now);
                    case RedButtonSchedule.PUBLISHING -> processPublishing(r, now);
                    case RedButtonSchedule.PUBLISHED -> processPublished(r, now);
                    default -> { }
                }
            } catch (Exception e) {
                log.error("[RedButton] processing schedule {} failed", r.getId(), e);
            }
        }
    }

    private void processPlanned(RedButtonSchedule r, LocalDateTime now) {
        int th = teaserHours();
        if (r.isFirstOfWeek() && !r.isTeaserSent() && th > 0 && !now.isBefore(r.getPlannedAt().minusHours(th))) {
            r.setTeaserSent(true);
            scheduleRepository.save(r);
            channelContentService.createExternalDraft(ChannelContentService.RED_BUTTON_TEASER, teaserText(), String.valueOf(r.getId()));
        }
        if (!r.isWarned() && !now.isBefore(r.getPlannedAt().minusMinutes(warnMin()))) {
            r.setWarned(true);
            scheduleRepository.save(r);
            if (now.isBefore(r.getPlannedAt())) {
                eventPublisher.publishEvent(new RedButtonEvent(this, RedButtonEvent.Kind.WARN, r.getId(), null));
            }
        }
        if (now.isBefore(r.getPlannedAt())) return;
        if (now.isAfter(r.getPlannedAt().plusMinutes(MAX_LATE_MIN))) {
            r.setStatus(RedButtonSchedule.MISSED);
            r.setStatusAt(now);
            scheduleRepository.save(r);
            eventPublisher.publishEvent(new RedButtonEvent(this, RedButtonEvent.Kind.ALERT, r.getId(),
                    "⚠️ Красная кнопка #" + r.getId() + " (+" + r.getPercent() + "%) не вышла вовремя (бот был недоступен?) и пропущена. Запустить сейчас: /redbutton now " + r.getPercent()));
            return;
        }
        r.setStatus(RedButtonSchedule.PUBLISHING);
        r.setStatusAt(now);
        scheduleRepository.save(r);
        eventPublisher.publishEvent(new RedButtonEvent(this, RedButtonEvent.Kind.PUBLISH, r.getId(), null));
    }

    private void processPublishing(RedButtonSchedule r, LocalDateTime now) {
        if (r.getStatusAt() != null && now.isAfter(r.getStatusAt().plusMinutes(PUBLISHING_TIMEOUT_MIN))) {
            r.setStatus(RedButtonSchedule.FAILED);
            r.setStatusAt(now);
            scheduleRepository.save(r);
            eventPublisher.publishEvent(new RedButtonEvent(this, RedButtonEvent.Kind.ALERT, r.getId(),
                    "⚠️ Красная кнопка #" + r.getId() + ": публикация зависла и остановлена, проверьте канал (/redbutton status)."));
        }
    }

    private void processPublished(RedButtonSchedule r, LocalDateTime now) {
        Optional<DailyBonus> b = r.getBonusId() == null ? Optional.empty() : dailyBonusRepository.findById(r.getBonusId());
        if (b.isEmpty() || b.get().getEndsAt() == null || now.isBefore(b.get().getEndsAt())) return;
        // Закрываем до события: правка поста выполняется один раз, при сбое бот сообщает админам (повторять бесконечно нельзя)
        r.setStatus(RedButtonSchedule.CLOSED);
        r.setStatusAt(now);
        scheduleRepository.save(r);
        eventPublisher.publishEvent(new RedButtonEvent(this, RedButtonEvent.Kind.CLOSE, r.getId(), null));
    }

    // ───────────────────────── для бота ─────────────────────────

    /** Запланированные, но ещё не вышедшие кнопки (для экрана админки: список с кнопками «Отменить»). */
    public List<RedButtonSchedule> planned() {
        return scheduleRepository.findByStatusInOrderByPlannedAt(List.of(RedButtonSchedule.PLANNED));
    }

    public Optional<RedButtonSchedule> find(Long id) {
        return id == null ? Optional.empty() : scheduleRepository.findById(id);
    }

    /** Создаёт запись бонуса и текст поста; null - если кнопку уже нельзя публиковать (не в статусе PUBLISHING). */
    public Prepared prepare(Long scheduleId) {
        Optional<RedButtonSchedule> opt = find(scheduleId);
        if (opt.isEmpty() || !RedButtonSchedule.PUBLISHING.equals(opt.get().getStatus())) return null;
        RedButtonSchedule r = opt.get();
        DailyBonus b = r.getBonusId() == null ? null : dailyBonusRepository.findById(r.getBonusId()).orElse(null);
        if (b == null) {
            b = new DailyBonus();
            b.setType(DailyBonus.RED);
            b.setBonusDate(LocalDate.now());
            b.setPercent(r.getPercent());
            b.setBudgetTotalRub(budgetRub());
            b = dailyBonusRepository.save(b);
            r.setBonusId(b.getId());
        }
        String text = postText(r);
        r.setPostText(text);
        scheduleRepository.save(r);
        return new Prepared(text, bonusCourseService.deepLink(b.getId()));
    }

    /** Пост вышел в канал: открывается окно нажатия. */
    public void onPublished(Long scheduleId, Integer messageId) {
        find(scheduleId).ifPresent(r -> {
            LocalDateTime now = LocalDateTime.now();
            r.setStatus(RedButtonSchedule.PUBLISHED);
            r.setStatusAt(now);
            if (messageId != null) r.setChannelMessageId(messageId.longValue());
            scheduleRepository.save(r);
            dailyBonusRepository.findById(r.getBonusId()).ifPresent(b -> {
                b.setPublishedAt(now);
                b.setEndsAt(now.plusMinutes(windowMin()));
                if (messageId != null) b.setChannelMessageId(messageId.longValue());
                dailyBonusRepository.save(b);
            });
        });
    }

    /** Публикация не удалась. true - повторим через минуту, false - попытки кончились (статус FAILED, админов предупреждает бот). */
    public boolean onPublishFailed(Long scheduleId) {
        Optional<RedButtonSchedule> opt = find(scheduleId);
        if (opt.isEmpty()) return false;
        RedButtonSchedule r = opt.get();
        LocalDateTime now = LocalDateTime.now();
        r.setAttempts(r.getAttempts() + 1);
        r.setStatusAt(now);
        boolean retry = r.getAttempts() < MAX_ATTEMPTS;
        if (retry) {
            r.setStatus(RedButtonSchedule.PLANNED);
            r.setPlannedAt(now.plusMinutes(1).truncatedTo(ChronoUnit.MINUTES));
        } else {
            r.setStatus(RedButtonSchedule.FAILED);
        }
        scheduleRepository.save(r);
        return retry;
    }

    /** Текст поста после закрытия окна: исходный пост + итог. */
    public String closedText(RedButtonSchedule r) {
        long pressed = r.getBonusId() == null ? 0 : userBonusRepository.countByBonusId(r.getBonusId());
        String base = r.getPostText() == null ? "🔴 <b>красная кнопка</b>" : r.getPostText();
        return base + "\n\n⏱ Кнопка погасла. Нажали: <b>" + pressed + "</b> " + playersWord(pressed) + ".";
    }

    private static String playersWord(long n) {
        long m = n % 100;
        if (m >= 11 && m <= 14) return "игроков";
        return switch ((int) (n % 10)) {
            case 1 -> "игрок";
            case 2, 3, 4 -> "игрока";
            default -> "игроков";
        };
    }

    /** Отмена запланированной кнопки админом (кнопка в предупреждении или /redbutton cancel). false - уже поздно или не найдена. */
    public boolean cancel(Long scheduleId) {
        Optional<RedButtonSchedule> opt = find(scheduleId);
        if (opt.isEmpty() || !RedButtonSchedule.PLANNED.equals(opt.get().getStatus())) return false;
        RedButtonSchedule r = opt.get();
        r.setStatus(RedButtonSchedule.CANCELLED);
        r.setStatusAt(LocalDateTime.now());
        scheduleRepository.save(r);
        return true;
    }

    /** Перепланировать текущую неделю: отменяет ещё не вышедшие кнопки и выбирает новые дни/время (/redbutton plan). */
    public int replanWeek() {
        for (RedButtonSchedule r : scheduleRepository.findByStatusInOrderByPlannedAt(List.of(RedButtonSchedule.PLANNED))) {
            cancel(r.getId());
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDate weekStart = now.toLocalDate().minusDays(now.getDayOfWeek().getValue() - 1L);
        put("bcr.planned", weekStart.toString());
        return planWeek(now, weekStart);
    }

    // ───────────────────────── тексты ─────────────────────────

    private String postText(RedButtonSchedule r) {
        LocalDateTime end = LocalDateTime.now().plusMinutes(windowMin()).plusHours(3);
        String until = String.format("%02d:%02d", end.getHour(), end.getMinute());
        String tpl = get("bcr.text.post");
        if (tpl == null || tpl.isBlank()) {
            tpl = "🔴 <b>красная кнопка</b>\n\n"
                    + "Редкий бонус: <b>+{p}%</b> к заявке на вывод в рублях или TON! Кнопка живёт всего {w} минут, до {until} по Москве. "
                    + "Нажал вовремя, и бонус действует {v} ч с момента включения.\n\n"
                    + "Бонус добавляется к любой заявке. Если заявка больше {cap} ₽, он считается только с первых {cap} ₽. Запас ограничен, жми, пока горит ⚡";
        }
        return tpl.replace("{p}", String.valueOf(r.getPercent()))
                .replace("{w}", String.valueOf(windowMin()))
                .replace("{until}", until)
                .replace("{v}", String.valueOf(bonusCourseService.redValidHours()))
                .replace("{cap}", String.valueOf(bonusCourseService.capRub()));
    }

    private String teaserText() {
        String tpl = get("bcr.text.teaser");
        if (tpl == null || tpl.isBlank()) {
            tpl = "🔴 <b>что-то красное на горизонте</b>\n\n"
                    + "Экси заметил: в ближайшие дни в канале появится красная кнопка. Она живёт совсем недолго и даёт крупный бонус к курсу вывода. "
                    + "Когда именно, не скажу 🤫 Следи за каналом и держи уведомления включёнными.";
        }
        return tpl;
    }

    /** Описание «Красной кнопки» простыми словами для экрана админки. Время: UTC+7 и Москва. */
    public String humanSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Что это: редкий пост с кнопкой и крупным бонусом. Выходит в случайное время БЕЗ согласования, кнопка живёт недолго, потом пост закрывается.\n\n");
        sb.append("Автоматические кнопки: ").append(isEnabled() ? "🔔 <b>включены</b>" : "🔕 <b>выключены</b>").append("\n");
        sb.append("Сколько: <b>").append(perWeek()).append("</b> в неделю, в случайные дни, время с ")
                .append(String.format("%02d:00", (startHour() + 7) % 24)).append(" до ").append(String.format("%02d:00", (endHour() + 7) % 24))
                .append(" по UTC+7 (").append(String.format("%02d:00", (startHour() + 3) % 24)).append("-").append(String.format("%02d:00", (endHour() + 3) % 24)).append(" по Москве).\n");
        sb.append("Бонус: +").append(minPercent()).append("…+").append(maxPercent()).append("%, действует ").append(bonusCourseService.redValidHours()).append(" ч после нажатия.\n");
        sb.append("Кнопка активна: <b>").append(windowMin()).append(" мин</b>. Бюджет одной кнопки: <b>").append(budgetRub()).append(" ₽</b>.\n");
        sb.append("Перед первой кнопкой недели выходит тизер за ").append(teaserHours()).append(" ч (через согласование), а за ").append(warnMin())
                .append(" мин до каждой кнопки вам приходит предупреждение с кнопкой «Отменить».\n\n");
        List<RedButtonSchedule> planned = planned();
        if (planned.isEmpty()) {
            sb.append("Запланированных кнопок нет.");
        } else {
            sb.append("<b>Запланировано:</b>\n");
            for (RedButtonSchedule r : planned) {
                LocalDateTime t = r.getPlannedAt().plusHours(7);
                LocalDateTime msk = r.getPlannedAt().plusHours(3);
                sb.append("• ").append(String.format("%02d.%02d %02d:%02d", t.getDayOfMonth(), t.getMonthValue(), t.getHour(), t.getMinute())).append(" по UTC+7 (")
                        .append(String.format("%02d:%02d", msk.getHour(), msk.getMinute())).append(" по Москве), +").append(r.getPercent()).append("%\n");
            }
        }
        List<RedButtonSchedule> last = scheduleRepository.findTop8ByOrderByPlannedAtDesc();
        for (RedButtonSchedule r : last) {
            if (RedButtonSchedule.PUBLISHED.equals(r.getStatus()) || RedButtonSchedule.CLOSED.equals(r.getStatus())) {
                sb.append("\nПоследняя вышла: ").append(String.format("%02d.%02d", r.getPlannedAt().plusHours(7).getDayOfMonth(), r.getPlannedAt().plusHours(7).getMonthValue()))
                        .append(", +").append(r.getPercent()).append("%, нажали ").append(r.getBonusId() == null ? 0 : userBonusRepository.countByBonusId(r.getBonusId())).append(".");
                break;
            }
        }
        return sb.toString();
    }

    public String statusText() {
        StringBuilder sb = new StringBuilder("🔴 <b>Красная кнопка: состояние</b>\n\n");
        sb.append("Автопланирование: ").append(isEnabled() ? "🔔 включено" : "🔕 выключено").append("\n");
        sb.append("Кнопок в неделю: ").append(perWeek()).append("; старт ").append(String.format("%02d:00", startHour())).append("-")
                .append(String.format("%02d:00", endHour() % 24)).append(" UTC; бонус +").append(minPercent()).append("…+").append(maxPercent())
                .append("% (шаг ").append(stepPercent()).append(")\n");
        sb.append("Окно нажатия ").append(windowMin()).append(" мин; бонус живёт ").append(bonusCourseService.redValidHours()).append(" ч; бюджет кнопки ")
                .append(budgetRub()).append(" ₽; тизер за ").append(teaserHours()).append(" ч; предупреждение за ").append(warnMin()).append(" мин\n\n");
        List<RedButtonSchedule> last = scheduleRepository.findTop8ByOrderByPlannedAtDesc();
        if (last.isEmpty()) {
            sb.append("Кнопок ещё не было. Запустить сейчас: <code>/redbutton now</code>.");
        } else {
            sb.append("Последние и ближайшие (UTC / МСК):\n");
            for (RedButtonSchedule r : last) {
                LocalDateTime msk = r.getPlannedAt().plusHours(3);
                sb.append("#").append(r.getId()).append(" ").append(r.getPlannedAt().toLocalDate()).append(" ")
                        .append(String.format("%02d:%02d", r.getPlannedAt().getHour(), r.getPlannedAt().getMinute())).append(" / ")
                        .append(String.format("%02d:%02d", msk.getHour(), msk.getMinute())).append(" +").append(r.getPercent()).append("% ")
                        .append(r.getStatus());
                if (r.getBonusId() != null) sb.append(", нажали ").append(userBonusRepository.countByBonusId(r.getBonusId()));
                sb.append("\n");
            }
        }
        return sb.toString();
    }
}
