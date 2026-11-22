package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.gamebot.platform.domain.model.AppSetting;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.model.QuestSubmission;
import ru.gamebot.platform.domain.repository.AppSettingRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;
import ru.gamebot.platform.event.AnalyticsAlertEvent;
import ru.gamebot.platform.event.PlayerPushEvent;

/**
 * Пилот «UGC в EXCASH» (решение владельца 21.11.2026): за ролики от 500 просмотров платим EXCASH (100 EXCASH = 1 ₽, вывод без лимитов),
 * а не EXC. Рамки пилота: бюджет 300 000 EXCASH (3 000 ₽) на весь пилот, граница 100 000 EXCASH в сутки на игрока, зачисление через 3 дня после
 * одобрения (удержание от накрутки), срок 14 дней. Ступень «до 500 просмотров» остаётся в обычных EXC. Бюджет кончился или прошло 14 дней - пилот
 * сам выключается, дальше всё как раньше (EXC). Запускает и останавливает владелец командой /ugcpilot (по умолчанию выключен).
 *
 * Удержанная часть лежит в QuestSubmission.heldCoins со статусом UGC_HOLD (не «PENDING»: тот статус разбирает планировщик подписок на каналы).
 * О старте, 80% бюджета, итогах и ежедневной сводке админам уходят личные сообщения (AnalyticsAlertEvent).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UgcPilotService {

    public static final String HELD = "UGC_HOLD";
    public static final String RELEASED = "UGC_RELEASED";
    public static final long BUDGET_EXCASH = 300_000L;
    public static final long DAILY_CAP_EXCASH = 100_000L;
    public static final int HOLD_DAYS = 3;
    public static final int DURATION_DAYS = 14;
    /** Ступень просмотров, с которой платим EXCASH (индекс в QuestService.UGC_VIEW_TIER_COINS: 1 = «500+»). */
    public static final int MIN_TIER = 1;

    private static final String KEY_ENABLED = "ugc.pilot.enabled";
    private static final String KEY_START = "ugc.pilot.start";
    private static final String KEY_ENDED = "ugc.pilot.ended";
    private static final String KEY_WARNED = "ugc.pilot.warned80";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final AppSettingRepository appSettingRepository;
    private final QuestSubmissionRepository questSubmissionRepository;
    private final SponsorBalanceService sponsorBalanceService;
    private final ExcTransactionService excTx;
    private final ApplicationEventPublisher eventPublisher;
    private final PlatformTransactionManager transactionManager;

    private String get(String key) {
        return appSettingRepository.findById(key).map(AppSetting::getValue).orElse(null);
    }

    private void put(String key, String value) {
        AppSetting s = appSettingRepository.findById(key).orElseGet(AppSetting::new);
        s.setKey(key);
        s.setValue(value);
        appSettingRepository.save(s);
    }

    private boolean flagOn() {
        String v = get(KEY_ENABLED);
        return v != null && "true".equalsIgnoreCase(v.trim());
    }

    private LocalDateTime startedAt() {
        try {
            String v = get(KEY_START);
            return v == null ? null : LocalDateTime.parse(v.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private long spent() {
        return questSubmissionRepository.sumHeldCoinsByStatus(HELD) + questSubmissionRepository.sumHeldCoinsByStatus(RELEASED);
    }

    private void alertAdmins(String html) {
        try {
            eventPublisher.publishEvent(new AnalyticsAlertEvent(this, html));
        } catch (Exception e) {
            log.warn("UGC pilot admin alert failed", e);
        }
    }

    private static String rub(long excash) {
        return String.format(java.util.Locale.US, "%,d", Math.round(excash / 100.0)).replace(',', ' ');
    }

    private static String num(long n) {
        return String.format(java.util.Locale.US, "%,d", n).replace(',', ' ');
    }

    /** Пилот идёт прямо сейчас (флаг включён, срок и бюджет не вышли, спонсорский баланс включён). Если срок или бюджет вышли - завершает пилот. */
    public synchronized boolean isActive() {
        if (!flagOn()) return false;
        LocalDateTime start = startedAt();
        if (start != null && LocalDateTime.now().isAfter(start.plusDays(DURATION_DAYS))) {
            finish("прошло " + DURATION_DAYS + " дней");
            return false;
        }
        if (spent() >= BUDGET_EXCASH) {
            finish("бюджет исчерпан");
            return false;
        }
        return sponsorBalanceService.enabled();
    }

    /** Сколько EXCASH из награды за этот ролик нужно удержать и зачислить через HOLD_DAYS (0 - платим как обычно в EXC). maxPayable - доля награды, оплачиваемая клубом без бонусов. */
    public synchronized long planExcash(AppUser user, Quest quest, Integer viewsTier, long maxPayable) {
        if (viewsTier == null || viewsTier < MIN_TIER || maxPayable <= 0) return 0;
        if (!QuestService.isUgcQuest(quest) || quest.isSponsored()) return 0;
        if (!isActive()) return 0;
        long spent = spent();
        long budgetLeft = BUDGET_EXCASH - spent;
        long usedToday = questSubmissionRepository.sumUgcPilotHeldByUserSince(user, LocalDateTime.now().minusHours(24).plusDays(HOLD_DAYS));
        long dailyLeft = DAILY_CAP_EXCASH - usedToday;
        long amount = Math.min(maxPayable, Math.min(budgetLeft, Math.max(0, dailyLeft)));
        if (amount <= 0) return 0;
        long after = spent + amount;
        if (after >= BUDGET_EXCASH) {
            finish("бюджет исчерпан (последняя выплата " + num(amount) + " EXCASH учтена)");
        } else if (after * 100 >= BUDGET_EXCASH * 80 && get(KEY_WARNED) == null) {
            put(KEY_WARNED, LocalDateTime.now().toString());
            alertAdmins("🧪 <b>Пилот UGC в EXCASH: израсходовано 80% бюджета</b>\n\n"
                    + "Потрачено " + num(after) + " из " + num(BUDGET_EXCASH) + " EXCASH (≈ " + rub(after) + " из " + rub(BUDGET_EXCASH) + " ₽).\n"
                    + "Когда бюджет кончится, пилот остановится сам и награда снова пойдёт в EXC.");
        }
        return amount;
    }

    private void finish(String reason) {
        if (!flagOn()) return;
        put(KEY_ENABLED, "false");
        put(KEY_ENDED, LocalDateTime.now() + " " + reason);
        long spent = spent();
        alertAdmins("🏁 <b>Пилот UGC в EXCASH завершён</b>: " + reason + "\n\n"
                + "Потрачено " + num(spent) + " из " + num(BUDGET_EXCASH) + " EXCASH (≈ " + rub(spent) + " ₽).\n"
                + "Новые ролики снова оплачиваются в EXC. Уже одобренные выплаты придут игрокам в срок (удержание " + HOLD_DAYS + " дня).\n"
                + "Посмотреть итоги: /ugcpilot");
    }

    /** Запуск пилота админом. Возвращает текст-ответ. */
    public synchronized String start() {
        if (!sponsorBalanceService.enabled()) {
            return "⚠️ Спонсорский баланс EXCASH выключен (app_settings sponsor.balance.enabled). Без него EXCASH нельзя вывести, пилот не запущен.";
        }
        if (isActive()) return "Пилот уже идёт.\n\n" + statusHtml();
        put(KEY_ENABLED, "true");
        put(KEY_START, LocalDateTime.now().toString());
        appSettingRepository.findById(KEY_ENDED).ifPresent(appSettingRepository::delete);
        appSettingRepository.findById(KEY_WARNED).ifPresent(appSettingRepository::delete);
        return "✅ Пилот запущен.\n\n" + statusHtml();
    }

    public synchronized String stop() {
        if (!flagOn()) return "Пилот и так не идёт.\n\n" + statusHtml();
        finish("остановлен вручную");
        return statusHtml();
    }

    public synchronized String statusHtml() {
        boolean on = flagOn();
        LocalDateTime start = startedAt();
        long spent = spent();
        long held = questSubmissionRepository.sumHeldCoinsByStatus(HELD);
        long heldCount = questSubmissionRepository.countByHeldStatus(HELD);
        long releasedCount = questSubmissionRepository.countByHeldStatus(RELEASED);
        long players = questSubmissionRepository.countUgcPilotPlayers();
        StringBuilder sb = new StringBuilder("🧪 <b>Пилот UGC в EXCASH</b>\n\n");
        if (on && start != null) {
            long day = ChronoUnit.DAYS.between(start, LocalDateTime.now()) + 1;
            sb.append("Статус: <b>идёт</b>, день ").append(Math.min(day, DURATION_DAYS)).append(" из ").append(DURATION_DAYS).append("\n");
            sb.append("Старт: ").append(start.format(DATE)).append(", закончится не позже ").append(start.plusDays(DURATION_DAYS).format(DATE)).append(" или когда кончится бюджет\n");
        } else {
            String ended = get(KEY_ENDED);
            sb.append("Статус: <b>").append(ended == null ? "не запущен" : "завершён").append("</b>\n");
            if (start != null) sb.append("Старт: ").append(start.format(DATE)).append("\n");
            if (ended != null) sb.append("Конец: ").append(ended).append("\n");
        }
        sb.append("Бюджет: потрачено ").append(num(spent)).append(" из ").append(num(BUDGET_EXCASH)).append(" EXCASH (≈ ")
                .append(rub(spent)).append(" из ").append(rub(BUDGET_EXCASH)).append(" ₽), осталось ").append(num(Math.max(0, BUDGET_EXCASH - spent))).append("\n");
        sb.append("Оплачено роликов: ").append(heldCount + releasedCount).append(" · игроков: ").append(players).append("\n");
        sb.append("Ждут зачисления (удержание ").append(HOLD_DAYS).append(" дня): ").append(heldCount).append(" на ").append(num(held)).append(" EXCASH\n\n");
        sb.append("Правила: от 500 просмотров платим EXCASH, до 500 просмотров EXC; зачисление через ").append(HOLD_DAYS)
                .append(" дня после проверки; не больше ").append(num(DAILY_CAP_EXCASH)).append(" EXCASH в сутки на игрока.\n\n");
        sb.append("<code>/ugcpilot start</code> · <code>/ugcpilot stop</code>");
        return sb.toString();
    }

    /** Ежедневная сводка админам, пока пилот идёт (09:00 по Москве): чтобы не забыть, что он идёт, и видеть расход. */
    @Scheduled(cron = "0 0 6 * * *")
    public void dailyDigest() {
        try {
            if (isActive()) alertAdmins("📆 <b>Ежедневная сводка пилота</b>\n\n" + statusHtml());
        } catch (Exception e) {
            log.warn("UGC pilot digest failed", e);
        }
    }

    /** Раз в 10 минут зачисляет EXCASH по роликам, у которых вышло удержание (порциями по 100: после простоя бота бэклог разбирается за несколько прогонов). */
    @Scheduled(fixedDelay = 600_000, initialDelay = 300_000)
    public void releaseDue() {
        try {
            List<QuestSubmission> due = questSubmissionRepository
                    .findTop100ByHeldStatusAndHeldReleaseAtBeforeOrderByHeldReleaseAtAsc(HELD, LocalDateTime.now());
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            for (QuestSubmission s : due) {
                Long id = s.getId();
                try {
                    tx.executeWithoutResult(st -> releaseOne(id));
                } catch (Exception e) {
                    log.warn("UGC pilot release failed for submission {}", id, e);
                    alertAdmins("⚠️ <b>Пилот UGC: не удалось зачислить EXCASH</b> по заявке #" + id + ". Выплата осталась в удержании, проверьте логи.");
                }
            }
        } catch (Exception e) {
            log.warn("UGC pilot release run failed", e);
        }
    }

    private void releaseOne(Long id) {
        QuestSubmission s = questSubmissionRepository.findWithUserAndQuestById(id).orElse(null);
        if (s == null || !HELD.equals(s.getHeldStatus()) || s.getHeldCoins() == null || s.getHeldCoins() <= 0) return;
        AppUser user = s.getUser();
        long amount = s.getHeldCoins();
        excTx.creditSponsor(user, amount, ExcTransactionService.QUEST, "UGC: " + s.getQuest().getTitle() + " (пилот, EXCASH)");
        s.setHeldStatus(RELEASED);
        questSubmissionRepository.save(s);
        eventPublisher.publishEvent(new PlayerPushEvent(this, user.getTelegramId(),
                "💎 <b>UGC: +" + num(amount) + " EXCASH зачислено</b>\n\n"
                        + "Ролик «" + s.getQuest().getTitle() + "» прошёл проверку. EXCASH можно вывести без лимитов, 100 EXCASH = 1 ₽.",
                "💎 Баланс", "menu:cat:wallet", null));
    }
}
