package ru.gamebot.platform.service;

import java.time.LocalDateTime;
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
 * UGC в EXCASH (пилот 21.11.2026, с 27.11.2026 по решению владельца постоянно, без срока и общего бюджета): за ролики от 500 просмотров платим EXCASH
 * (100 EXCASH = 1 ₽, вывод без лимитов), в разделе UGC игрок видит рубли. Защита: проверка модератором, зачисление через 3 дня после одобрения (удержание
 * от накрутки) и граница 150 000 EXCASH в сутки на игрока (равна максимальной награде за один ролик). Ступень «до 500 просмотров» остаётся в обычных EXC. Включено по умолчанию, пока работает
 * спонсорский баланс; аварийно остановить и вернуть EXC (вместе с прежними текстами) можно командой /ugcpilot stop.
 *
 * Удержанная часть лежит в QuestSubmission.heldCoins со статусом UGC_HOLD (не «PENDING»: тот статус разбирает планировщик подписок на каналы).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UgcPilotService {

    public static final String HELD = "UGC_HOLD";
    public static final String RELEASED = "UGC_RELEASED";
    public static final long DAILY_CAP_EXCASH = 150_000L;
    public static final int HOLD_DAYS = 3;
    /** Ступень просмотров, с которой платим EXCASH (индекс в QuestService.UGC_VIEW_TIER_COINS: 1 = «500+»). */
    public static final int MIN_TIER = 1;

    private static final String KEY_STOPPED = "ugc.excash.stopped";
    private static final String KEY_DIGEST_SPENT = "ugc.excash.digest.spent";

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

    private static String escape(String v) {
        return v == null ? "" : v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String num(long n) {
        return String.format(java.util.Locale.US, "%,d", n).replace(',', ' ');
    }

    /** Платим ли за UGC в EXCASH: не остановлено админом и включён спонсорский баланс. */
    public boolean isActive() {
        return !"true".equals(get(KEY_STOPPED)) && sponsorBalanceService.enabled();
    }

    /** Сколько EXCASH из награды за этот ролик нужно удержать и зачислить через HOLD_DAYS (0 - платим как обычно в EXC). maxPayable - доля награды, оплачиваемая клубом без бонусов. */
    public synchronized long planExcash(AppUser user, Quest quest, Integer viewsTier, long maxPayable) {
        if (viewsTier == null || viewsTier < MIN_TIER || maxPayable <= 0) return 0;
        if (!QuestService.isUgcQuest(quest) || quest.isSponsored()) return 0;
        if (!isActive()) return 0;
        long usedToday = questSubmissionRepository.sumUgcPilotHeldByUserSince(user, LocalDateTime.now().minusHours(24).plusDays(HOLD_DAYS));
        long dailyLeft = DAILY_CAP_EXCASH - usedToday;
        return Math.max(0, Math.min(maxPayable, dailyLeft));
    }

    /** Снять аварийную остановку. Возвращает текст-ответ. */
    public synchronized String start() {
        if (!sponsorBalanceService.enabled()) {
            return "⚠️ Спонсорский баланс EXCASH выключен (app_settings sponsor.balance.enabled). Без него EXCASH нельзя вывести, ролики оплачиваются в EXC.";
        }
        put(KEY_STOPPED, "false");
        return "✅ Выплаты UGC в EXCASH включены.\n\n" + statusHtml();
    }

    public synchronized String stop() {
        put(KEY_STOPPED, "true");
        return "⏸ Выплаты UGC в EXCASH остановлены: новые ролики оплачиваются в EXC, тексты раздела вернулись к EXC. Уже одобренные выплаты придут в срок.\n\n" + statusHtml();
    }

    public String statusHtml() {
        long spent = spent();
        long held = questSubmissionRepository.sumHeldCoinsByStatus(HELD);
        long heldCount = questSubmissionRepository.countByHeldStatus(HELD);
        long releasedCount = questSubmissionRepository.countByHeldStatus(RELEASED);
        long players = questSubmissionRepository.countUgcPilotPlayers();
        return "🤳 <b>UGC в EXCASH</b>\n\n"
                + "Статус: <b>" + (isActive() ? "платим в EXCASH" : "остановлено, платим в EXC") + "</b>\n"
                + "Выплачено и в удержании всего: " + num(spent) + " EXCASH (≈ " + rub(spent) + " ₽)\n"
                + "Оплачено роликов: " + (heldCount + releasedCount) + " · игроков: " + players + "\n"
                + "Ждут зачисления (удержание " + HOLD_DAYS + " дня): " + heldCount + " на " + num(held) + " EXCASH\n\n"
                + "Правила: от 500 просмотров платим EXCASH, до 500 просмотров EXC; зачисление через " + HOLD_DAYS
                + " дня после проверки; не больше " + num(DAILY_CAP_EXCASH) + " EXCASH в сутки на игрока; общего бюджета и срока нет.\n\n"
                + "<code>/ugcpilot start</code> · <code>/ugcpilot stop</code>";
    }

    /** Сводка админам в 09:00 по Москве, только если за сутки появились новые выплаты: общего бюджета нет, расход должен быть на виду. */
    @Scheduled(cron = "0 0 6 * * *")
    public void dailyDigest() {
        try {
            String spent = String.valueOf(spent());
            String last = get(KEY_DIGEST_SPENT);
            if (spent.equals(last == null ? "0" : last)) return;
            put(KEY_DIGEST_SPENT, spent);
            alertAdmins("📆 <b>Сводка: новые выплаты за UGC</b>\n\n" + statusHtml());
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
                "💎 <b>UGC: +" + GameStageRewardService.rubWithKopecks(amount) + " ₽ зачислено</b>\n\n"
                        + "Ролик «" + escape(s.getQuest().getTitle()) + "» прошёл проверку. Начислили " + num(amount) + " EXCASH, их можно вывести без лимитов.",
                "💎 Баланс", "menu:cat:wallet", null));
    }
}
