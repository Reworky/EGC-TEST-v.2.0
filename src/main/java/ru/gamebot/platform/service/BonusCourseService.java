package ru.gamebot.platform.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.model.AppSetting;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.ChannelPostDraft;
import ru.gamebot.platform.domain.model.DailyBonus;
import ru.gamebot.platform.domain.model.RewardRequest;
import ru.gamebot.platform.domain.model.UserBonus;
import ru.gamebot.platform.domain.repository.AppSettingRepository;
import ru.gamebot.platform.domain.repository.DailyBonusRepository;
import ru.gamebot.platform.domain.repository.UserBonusRepository;

/**
 * «Курс дня» (21.10.2026, ТЗ владельца): ежедневный пост с кнопкой, игрок активирует временный бонус к сумме заявки на вывод в рублях и TON.
 * Все цифры - в app_settings (ключи bc.*), меняются командой /course без деплоя. Время в UTC.
 * Бонус считается ОДИН раз при создании заявки (RewardService, под блокировкой игрока): потолок суммы, резерв бюджета дня; при отмене/отклонении заявки бюджет возвращается.
 * По умолчанию автопост ВЫКЛЮЧЕН (/course on).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BonusCourseService {

    public static final String DEFAULT_WEIGHTS = "5:35,10:30,15:20,25:12,50:3";
    private static final int DEFAULT_HOUR_UTC = 11;
    private static final long DEFAULT_CAP_RUB = 100;
    private static final long DEFAULT_BUDGET_RUB = 35;
    /** Бонус действует до этого часа UTC (21 = полночь по Москве). */
    private static final int DEFAULT_END_HOUR_UTC = 21;

    private final AppSettingRepository settingRepository;
    private final DailyBonusRepository dailyBonusRepository;
    private final UserBonusRepository userBonusRepository;
    private final HealthRatioService healthRatioService;
    /** Через ObjectProvider: ChannelContentService сам зависит от RewardService, а RewardService - от этого сервиса (прямая зависимость дала бы цикл бинов). */
    private final ObjectProvider<ChannelContentService> channelContentProvider;

    @Value("${app.bot-username:}")
    private String botUsername;

    public enum ActivationStatus { OK, ALREADY, NOT_FOUND, NOT_STARTED, CLOSED, BUDGET_OVER, DENIED }

    public record Activation(ActivationStatus status, int percent, LocalDateTime validUntil, String type) { }

    /** Что применено к заявке на вывод: бонус, процент и сумма в рублях (добавляется к базовой сумме). */
    public record Applied(Long bonusId, int percent, long bonusRub) { }

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

    public boolean isEnabled() { return "1".equals(get("bc.enabled")); }

    public void setEnabled(boolean on) { put("bc.enabled", on ? "1" : "0"); }

    public int postHourUtc() { return (int) Math.max(0, Math.min(23, parseLong(get("bc.hour"), DEFAULT_HOUR_UTC))); }

    public long capRub() { return Math.max(1, parseLong(get("bc.cap"), DEFAULT_CAP_RUB)); }

    public long dailyBudgetRub() { return Math.max(0, parseLong(get("bc.budget"), DEFAULT_BUDGET_RUB)); }

    public int endHourUtc() { return (int) Math.max(1, Math.min(24, parseLong(get("bc.endhour"), DEFAULT_END_HOUR_UTC))); }

    public String weights() {
        String w = get("bc.weights");
        return parseWeights(w).isEmpty() ? DEFAULT_WEIGHTS : w.trim();
    }

    /** "5:35,10:30" -> пары (процент, вес); неверные куски пропускаются. */
    static List<int[]> parseWeights(String raw) {
        List<int[]> out = new ArrayList<>();
        if (raw == null) return out;
        for (String part : raw.split(",")) {
            String[] kv = part.trim().split(":");
            if (kv.length != 2) continue;
            try {
                int pct = Integer.parseInt(kv[0].trim());
                int w = Integer.parseInt(kv[1].trim());
                if (pct > 0 && pct <= 500 && w > 0) out.add(new int[]{pct, w});
            } catch (NumberFormatException ignored) {
                // кусок пропускаем
            }
        }
        return out;
    }

    /** Команда /course set <ключ> <значение>: проверяет диапазон, возвращает текст ответа админу. */
    public String setParam(String key, String value) {
        long v = parseLong(value, Long.MIN_VALUE);
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "hour" -> {
                if (v < 0 || v > 23) yield "Час по UTC: число от 0 до 23.";
                put("bc.hour", String.valueOf(v));
                yield "🕐 Пост «Курс дня» формируется в " + String.format("%02d:00", v) + " UTC.";
            }
            case "cap" -> {
                if (v < 1 || v > 100000) yield "Потолок суммы заявки в рублях: число от 1 до 100000.";
                put("bc.cap", String.valueOf(v));
                yield "✅ Бонус считается максимум с " + v + " ₽ на заявку.";
            }
            case "budget" -> {
                if (v < 0 || v > 1000000) yield "Дневной бюджет бонусов в рублях: число от 0 до 1000000.";
                put("bc.budget", String.valueOf(v));
                yield "✅ Бюджет бонусов на день: " + v + " ₽ (действует для новых постов).";
            }
            case "endhour" -> {
                if (v < 1 || v > 24) yield "Час окончания бонуса по UTC: число от 1 до 24 (21 = полночь по Москве).";
                put("bc.endhour", String.valueOf(v));
                yield "✅ Бонус дня действует до " + String.format("%02d:00", v % 24) + " UTC.";
            }
            case "weights" -> {
                if (parseWeights(value).isEmpty()) yield "Формат: <code>/course set weights 5:35,10:30,15:20,25:12,50:3</code> (процент:вес через запятую).";
                put("bc.weights", value.trim());
                yield "✅ Веса бонуса дня: " + value.trim() + ", средний " + String.format(Locale.ROOT, "%.2f", averagePercent(value)) + "%.";
            }
            default -> "Параметры: hour, cap, budget, endhour, weights.";
        };
    }

    static double averagePercent(String raw) {
        List<int[]> w = parseWeights(raw);
        double total = 0, sum = 0;
        for (int[] p : w) { total += p[1]; sum += (double) p[0] * p[1]; }
        return total == 0 ? 0 : sum / total;
    }

    int pickPercent() {
        List<int[]> w = parseWeights(weights());
        int total = 0;
        for (int[] p : w) total += p[1];
        int r = ThreadLocalRandom.current().nextInt(total);
        for (int[] p : w) {
            r -= p[1];
            if (r < 0) return p[0];
        }
        return w.get(0)[0];
    }

    // ───────────────────────── расписание и пост ─────────────────────────

    /** Раз в 10 минут: в заданный час UTC и если сегодня пост ещё не формировался - создаёт бонус дня и черновик поста (дальше обычное одобрение админом). */
    @Scheduled(fixedDelay = 600_000, initialDelay = 200_000)
    public void dailyTick() {
        try {
            if (!isEnabled()) return;
            LocalDateTime now = LocalDateTime.now();
            if (now.getHour() != postHourUtc()) return;
            String today = LocalDate.now().toString();
            if (today.equals(get("bc.last"))) return;
            put("bc.last", today);
            createDailyDraft(null);
        } catch (Exception e) {
            log.error("[BonusCourse] daily tick failed", e);
        }
    }

    /** percent == null - выбрать по весам. Ручной запуск (/course now) создаёт новый бонус даже если сегодня уже был. */
    public Optional<ChannelPostDraft> createDailyDraft(Integer percent) {
        int pct = percent != null ? percent : pickPercent();
        DailyBonus b = new DailyBonus();
        b.setBonusDate(LocalDate.now());
        b.setPercent(pct);
        b.setBudgetTotalRub(dailyBudgetRub());
        b = dailyBonusRepository.save(b);
        String text = buildPostText(pct, endsAtFor(LocalDateTime.now()));
        ChannelPostDraft d = channelContentProvider.getObject().createExternalDraft(ChannelContentService.DAILY_BUTTON, text, String.valueOf(b.getId()));
        log.info("[BonusCourse] daily bonus {} (+{}%) created, draft {}", b.getId(), pct, d.getId());
        return Optional.of(d);
    }

    /** Момент окончания бонуса для поста, вышедшего в now: ближайший час окончания (UTC); если до него меньше 2 часов - 2 часа от публикации. */
    LocalDateTime endsAtFor(LocalDateTime now) {
        LocalDateTime end = now.toLocalDate().atStartOfDay().plusHours(endHourUtc());
        return end.isBefore(now.plusHours(2)) ? now.plusHours(2) : end;
    }

    private String buildPostText(int pct, LocalDateTime endsAt) {
        double ratio = healthRatioService.getCurrentRatio();
        double base = ratio * 10.0;
        double boosted = base * (1 + pct / 100.0);
        Locale ru = Locale.forLanguageTag("ru");
        LocalDateTime msk = endsAt.plusHours(3);
        StringBuilder sb = new StringBuilder("📈 <b>курс дня</b>\n\n");
        sb.append("Сегодня курс вывода выше: <b>+").append(pct).append("%</b> к сумме заявки 🔥\n");
        sb.append("1 000 EXC = <b>").append(String.format(ru, "%.2f", boosted)).append(" ₽</b> вместо ").append(String.format(ru, "%.2f", base)).append(" ₽.\n\n");
        sb.append("Как забрать: жми кнопку ниже, бот включит бонус. Он действует до ").append(String.format("%02d:%02d", msk.getHour(), msk.getMinute()))
                .append(" по Москве и работает для заявок на вывод в рублях и в TON, которые ты создашь после включения. ");
        sb.append("Бонус добавляется к любой заявке на вывод. Если заявка больше ").append(capRub()).append(" ₽, бонус считается только с первых ").append(capRub())
                .append(" ₽. Общий запас бонусов на сегодня ограничен: кто успел, тот и забрал.\n\n");
        sb.append("Успеешь вывести сегодня? ⚡");
        return sb.toString();
    }

    public String deepLink(Long bonusId) {
        return "https://t.me/" + botUsername + "?start=btn_" + bonusId;
    }

    public Optional<DailyBonus> findBonus(Long id) {
        return id == null ? Optional.empty() : dailyBonusRepository.findById(id);
    }

    /** Пост опубликован в канале (одобрение админом): открывается окно активации. */
    public void onPostPublished(Long bonusId, Integer messageId) {
        Optional<DailyBonus> opt = findBonus(bonusId);
        if (opt.isEmpty()) {
            log.warn("[BonusCourse] published post for unknown bonus {}", bonusId);
            return;
        }
        DailyBonus b = opt.get();
        LocalDateTime now = LocalDateTime.now();
        b.setPublishedAt(now);
        b.setEndsAt(endsAtFor(now));
        if (messageId != null) b.setChannelMessageId(messageId.longValue());
        dailyBonusRepository.save(b);
    }

    // ───────────────────────── активация ─────────────────────────

    /** Нажатие на кнопку под постом (до проверки подписки): для конверсии нажатие -> активация во вкладке аналитики. Сбой не мешает активации. */
    public void recordClick(Long bonusId) {
        try {
            if (bonusId != null) dailyBonusRepository.addClick(bonusId);
        } catch (Exception e) {
            log.warn("[BonusCourse] click counter failed for bonus {}", bonusId, e);
        }
    }

    /** Подписку на канал проверяет бот ДО вызова (нужен Telegram API). Метод намеренно без @Transactional: гонка двойного нажатия закрыта
     *  уникальным индексом (игрок, бонус), и его нарушение не должно помечать внешнюю транзакцию rollback-only. */
    public Activation activate(AppUser user, Long bonusId) {
        Optional<DailyBonus> opt = findBonus(bonusId);
        if (opt.isEmpty()) return new Activation(ActivationStatus.NOT_FOUND, 0, null, null);
        DailyBonus b = opt.get();
        if (user.isBlocked() || user.isFraudSuspect() || user.isWithdrawalFrozen()) {
            return new Activation(ActivationStatus.DENIED, b.getPercent(), null, b.getType());
        }
        if (b.getPublishedAt() == null || b.getEndsAt() == null) return new Activation(ActivationStatus.NOT_STARTED, b.getPercent(), null, b.getType());
        LocalDateTime now = LocalDateTime.now();
        if (!now.isBefore(b.getEndsAt())) return new Activation(ActivationStatus.CLOSED, b.getPercent(), b.getEndsAt(), b.getType());
        if (userBonusRepository.existsByUserIdAndBonusId(user.getId(), b.getId())) {
            return new Activation(ActivationStatus.ALREADY, b.getPercent(), existingValidUntil(user, b), b.getType());
        }
        if (b.getBudgetTotalRub() - b.getBudgetUsedRub() < 1) return new Activation(ActivationStatus.BUDGET_OVER, b.getPercent(), b.getEndsAt(), b.getType());
        UserBonus ub = new UserBonus();
        ub.setUserId(user.getId());
        ub.setBonusId(b.getId());
        ub.setPercent(b.getPercent());
        ub.setActivatedAt(now);
        // DAILY: до конца окна бонуса дня; RED: несколько часов с момента активации (окно нажатия короткое, а бонус живёт дольше)
        LocalDateTime validUntil = DailyBonus.RED.equals(b.getType()) ? now.plusHours(redValidHours()) : b.getEndsAt();
        ub.setValidUntil(validUntil);
        try {
            userBonusRepository.save(ub);
        } catch (DataIntegrityViolationException e) {
            return new Activation(ActivationStatus.ALREADY, b.getPercent(), existingValidUntil(user, b), b.getType());
        }
        return new Activation(ActivationStatus.OK, b.getPercent(), validUntil, b.getType());
    }

    /** Сколько часов после нажатия действует бонус красной кнопки. */
    public int redValidHours() { return (int) Math.max(1, Math.min(24, parseLong(get("bcr.validhours"), 3))); }

    public void setRedValidHours(int hours) { put("bcr.validhours", String.valueOf(Math.max(1, Math.min(24, hours)))); }

    private LocalDateTime existingValidUntil(AppUser user, DailyBonus b) {
        return userBonusRepository.findByUserIdAndBonusId(user.getId(), b.getId()).map(UserBonus::getValidUntil).orElse(b.getEndsAt());
    }

    // ───────────────────────── применение к выводу ─────────────────────────

    /** Вызывается из RewardService при создании заявки на вывод в рублях/TON, под блокировкой строки игрока. baseRub - сумма заявки до бонуса.
     *  Берётся самый большой из активных бонусов, у которого ещё есть бюджет (бонусы не суммируются); если остаток бюджета меньше расчётного бонуса,
     *  выдаётся остаток. Любой сбой не должен ломать вывод: пишем в лог (его читает монитор ошибок) и возвращаем null. */
    public Applied applyToWithdrawal(AppUser lockedUser, long baseRub) {
        try {
            if (baseRub <= 0) return null;
            List<UserBonus> active = new ArrayList<>(userBonusRepository.findByUserIdAndValidUntilAfter(lockedUser.getId(), LocalDateTime.now()));
            if (active.isEmpty()) return null;
            active.sort(Comparator.comparingInt(UserBonus::getPercent).reversed());
            long capped = Math.min(baseRub, capRub());
            for (UserBonus ub : active) {
                long raw = Math.round(capped * ub.getPercent() / 100.0);
                if (raw < 1) continue;
                Optional<DailyBonus> opt = dailyBonusRepository.findByIdForUpdate(ub.getBonusId());
                if (opt.isEmpty()) continue;
                DailyBonus b = opt.get();
                long amount = Math.min(raw, b.getBudgetTotalRub() - b.getBudgetUsedRub());
                if (amount < 1) continue;
                b.setBudgetUsedRub(b.getBudgetUsedRub() + amount);
                dailyBonusRepository.save(b);
                return new Applied(b.getId(), ub.getPercent(), amount);
            }
            return null;
        } catch (Exception e) {
            log.error("[BonusCourse] applyToWithdrawal failed for user {} (withdrawal continues without bonus)", lockedUser.getId(), e);
            return null;
        }
    }

    /** Заявка отменена или отклонена: резерв бюджета возвращается. */
    public void releaseBudget(RewardRequest req) {
        try {
            if (req.getBonusId() == null || req.getBonusAmountRub() <= 0) return;
            dailyBonusRepository.findByIdForUpdate(req.getBonusId()).ifPresent(b -> {
                b.setBudgetUsedRub(Math.max(0, b.getBudgetUsedRub() - req.getBonusAmountRub()));
                dailyBonusRepository.save(b);
            });
        } catch (Exception e) {
            log.error("[BonusCourse] releaseBudget failed for request {}", req.getId(), e);
        }
    }

    /** Активный сейчас бонус игрока (для подсказок в боте): самый большой процент, null если нет. */
    public Optional<UserBonus> activeFor(AppUser user) {
        return userBonusRepository.findByUserIdAndValidUntilAfter(user.getId(), LocalDateTime.now()).stream()
                .max(Comparator.comparingInt(UserBonus::getPercent));
    }

    // ───────────────────────── админка ─────────────────────────

    /** Описание «Курса дня» простыми словами для экрана админки (без команд и технических параметров). Время: UTC+7 и Москва. */
    public String humanSummary() {
        int h = postHourUtc();
        int endMsk = (endHourUtc() + 3) % 24;
        List<int[]> w = parseWeights(weights());
        int min = w.stream().mapToInt(a -> a[0]).min().orElse(0);
        int max = w.stream().mapToInt(a -> a[0]).max().orElse(0);
        StringBuilder sb = new StringBuilder();
        sb.append("Что это: каждый день в канале пост с кнопкой. Игрок нажимает, и бот добавляет бонус к сумме его заявки на вывод (рубли и TON).\n\n");
        sb.append("Автопост: ").append(isEnabled() ? "🔔 <b>включён</b>" : "🔕 <b>выключен</b>").append("\n");
        sb.append("Время поста: <b>").append(String.format("%02d:00", (h + 7) % 24)).append("</b> по UTC+7 (")
                .append(String.format("%02d:00", (h + 3) % 24)).append(" по Москве). Пост сначала приходит вам на согласование.\n");
        sb.append("Бонус действует до ").append(String.format("%02d:00", endMsk)).append(" по Москве.\n");
        sb.append("Размер бонуса: от +").append(min).append("% до +").append(max).append("%, в среднем ").append(String.format(Locale.ROOT, "%.1f", averagePercent(weights()))).append("%.\n");
        sb.append("Потолок: бонус добавляется к любой заявке, но если она больше <b>").append(capRub()).append(" ₽</b>, считается только с первых ").append(capRub()).append(" ₽.\n");
        sb.append("Бюджет на день: <b>").append(dailyBudgetRub()).append(" ₽</b> (когда он кончится, бонус перестаёт выдаваться).\n\n");
        Optional<DailyBonus> last = dailyBonusRepository.findFirstByOrderByIdDesc();
        if (last.isEmpty() || !DailyBonus.DAILY.equals(last.get().getType())) {
            sb.append("Постов «Курс дня» ещё не было.");
        } else {
            DailyBonus b = last.get();
            sb.append("Последний: +").append(b.getPercent()).append("% (").append(String.format("%02d.%02d", b.getBonusDate().getDayOfMonth(), b.getBonusDate().getMonthValue())).append("), ")
                    .append(b.getPublishedAt() == null ? "пост ещё не опубликован" : "включили " + userBonusRepository.countByBonusId(b.getId()) + ", нажатий " + b.getClicks())
                    .append(", бюджет ").append(b.getBudgetUsedRub()).append(" из ").append(b.getBudgetTotalRub()).append(" ₽.");
        }
        return sb.toString();
    }

    public String statusText() {
        StringBuilder sb = new StringBuilder("📈 <b>Курс дня: состояние</b>\n\n");
        int h = postHourUtc();
        sb.append("Автопост: ").append(isEnabled() ? "🔔 включён" : "🔕 выключен").append(", ")
                .append(String.format("%02d:00", h)).append(" UTC (").append(String.format("%02d:00", (h + 3) % 24)).append(" по Москве, ")
                .append(String.format("%02d:00", (h + 7) % 24)).append(" по UTC+7)\n");
        sb.append("Бонус действует до ").append(String.format("%02d:00", endHourUtc() % 24)).append(" UTC\n");
        sb.append("Веса: <code>").append(weights()).append("</code>, средний ").append(String.format(Locale.ROOT, "%.2f", averagePercent(weights()))).append("%\n");
        sb.append("Потолок суммы заявки: ").append(capRub()).append(" ₽; бюджет на день: ").append(dailyBudgetRub()).append(" ₽\n\n");
        Optional<DailyBonus> last = dailyBonusRepository.findFirstByOrderByIdDesc();
        if (last.isEmpty()) {
            sb.append("Бонусов ещё не было. Создать сейчас: <code>/course now</code>.");
        } else {
            DailyBonus b = last.get();
            sb.append("Последний: ").append(b.getType()).append(" +").append(b.getPercent()).append("% (").append(b.getBonusDate()).append("), ")
                    .append(b.getPublishedAt() == null ? "пост ещё не опубликован" : "опубликован " + b.getPublishedAt() + ", до " + b.getEndsAt()).append("\n");
            sb.append("Активаций: ").append(userBonusRepository.countByBonusId(b.getId())).append("; бюджет: ")
                    .append(b.getBudgetUsedRub()).append(" из ").append(b.getBudgetTotalRub()).append(" ₽");
        }
        return sb.toString();
    }
}
