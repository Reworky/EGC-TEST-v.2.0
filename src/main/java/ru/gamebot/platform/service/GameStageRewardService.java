package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.gamebot.platform.bot.GamePlatformBot;
import ru.gamebot.platform.domain.model.AppSetting;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.repository.AppSettingRepository;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.QuestRepository;

/**
 * Промежуточный этап игрового квеста (26.11.2026, владелец: у Sacralium две цели, регистрация без награды). Этап 1 «Активный игрок» - отдельное начисление, квест им не закрывается;
 * этап 2 «Активный игрок 2» закрывает квест обычным одобрением (награда квеста). Этап 1 выдаётся игроку один раз: отметка в app_settings пишется в той же транзакции,
 * что и начисление, под блокировкой строки игрока.
 */
@Slf4j
@Service
public class GameStageRewardService {

    /** Этап 1 Sacralium, по 30% выплаты: Россия 49,20 ₽ - игроку 14,76 ₽, остальные страны 24,60 ₽ - 7,38 ₽ (100 EXCASH = 1 ₽). */
    public static final long SACRALIUM_STAGE1_EXC = 738L;
    public static final long SACRALIUM_STAGE1_RU_EXC = 1_476L;

    /** Доплата за игрока из России: сеть платит за него больше. Награда квеста - 30% выплаты за другие страны, доплата доводит до 30% выплаты за Россию (100 EXCASH = 1 ₽). */
    public record RussiaExtra(String gameName, long exc) {}

    public static final java.util.Map<String, RussiaExtra> RUSSIA_EXTRAS = java.util.Map.of(
            "sacralium", new RussiaExtra("Sacralium", 9_834L),             // этап 2: 655,70 ₽ против 327,90 ₽: 196,71 ₽ и 98,37 ₽
            "pathofdoom", new RussiaExtra("Путь Судьбы", 1_800L),          // 120 ₽ против 60 ₽: 36 ₽ и 18 ₽
            "demonslayer", new RussiaExtra("Охотник на Демонов", 960L),    // 96 ₽ против 64 ₽: 28,80 ₽ и 19,20 ₽
            "jade", new RussiaExtra("Нефритовое Царство", 720L),           // 80 ₽ против 56 ₽: 24 ₽ и 16,80 ₽
            "dragonegg", new RussiaExtra("Яйцо Дракона", 720L));           // 72 ₽ против 48 ₽: 21,60 ₽ и 14,40 ₽

    /** EXCASH в рубли с копейками: 1920 -> «19,20», 1800 -> «18». */
    public static String rubWithKopecks(long exc) {
        return exc % 100 == 0 ? String.valueOf(exc / 100) : exc / 100 + "," + String.format("%02d", exc % 100);
    }

    /** Шаг квеста с отдельной оплатой. */
    public record Stage(String label, long exc) {}

    /** «Родина РП» (26.11.2026, карточка одобрена владельцем): каждый шаг - 30% выплаты сети, начисляется один раз на игрока. */
    public static final java.util.Map<String, Stage> RODINA_STAGES = new java.util.LinkedHashMap<>();
    static {
        RODINA_STAGES.put("ticket", new Stage("военный билет", 540L));          // 18 ₽
        RODINA_STAGES.put("org", new Stage("вступление в организацию", 540L));  // 18 ₽
        RODINA_STAGES.put("active", new Stage("активный игрок", 720L));         // 24 ₽
        RODINA_STAGES.put("deposit", new Stage("первое пополнение", 3_600L));   // 120 ₽
    }
    private static final String RODINA_MARK = "gamestage.rodina.";

    private final QuestService questService;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final AppUserRepository appUserRepository;
    private final AppSettingRepository appSettingRepository;
    private final QuestRepository questRepository;
    private final ExcTransactionService excTransactionService;
    private final UserService userService;
    private final SponsorBalanceService sponsorBalanceService;
    private final GamePlatformBot bot;
    private final TransactionTemplate tx;

    public GameStageRewardService(AppUserRepository appUserRepository, AppSettingRepository appSettingRepository, QuestRepository questRepository,
                                  ExcTransactionService excTransactionService, UserService userService, SponsorBalanceService sponsorBalanceService,
                                  @Lazy GamePlatformBot bot, PlatformTransactionManager txManager,
                                  QuestService questService, org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.questService = questService;
        this.eventPublisher = eventPublisher;
        this.appUserRepository = appUserRepository;
        this.appSettingRepository = appSettingRepository;
        this.questRepository = questRepository;
        this.excTransactionService = excTransactionService;
        this.userService = userService;
        this.sponsorBalanceService = sponsorBalanceService;
        this.bot = bot;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Доплата за Россию к уже одобренному игровому квесту: один раз на игрока и игру. Ответ как у этапа: «ok» - начислено сейчас или раньше. */
    public String payRussiaExtra(String subid, String offerId) {
        RussiaExtra extra = RUSSIA_EXTRAS.get(offerId);
        if (extra == null) return "unknown offer";
        long telegramId;
        try {
            telegramId = Long.parseLong(String.valueOf(subid).trim());
        } catch (NumberFormatException e) {
            return "bad subaccount";
        }
        AppUser found = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (found == null) return "user not found";
        if (payOnce(found.getId(), "gamestage." + offerId + ".ru." + found.getId(), extra.exc(), extra.gameName() + ": доплата за Россию")) {
            try {
                bot.caseAutoNotify(telegramId, "💰 <b>" + extra.gameName() + ": ещё +" + rubWithKopecks(extra.exc()) + " ₽</b>\n\nЭто доплата игрокам из России: доначислили "
                        + extra.exc() + " " + (sponsorBalanceService.enabled() ? "EXCASH, они выводятся в рубли без лимитов." : "EXC."), null);
            } catch (Exception e) {
                log.warn("[GameStage] notice failed for {}", telegramId, e);
            }
        }
        return "ok";
    }

    private record StagePaid(String answer, String stageKey, int done, Long closedSubmissionId) {
        static StagePaid of(String answer) { return new StagePaid(answer, null, 0, null); }
    }

    /**
     * Шаг «Родины РП». stageKey: ticket, org, active, deposit или job (сеть не назвала цель: билет и организация стоят одинаково, занимаем свободный из двух).
     * conversionId - ID действия в сети, защищает от повтора. Квест остаётся взятым, пока не оплачены все четыре шага; последний закрывает его обычным одобрением.
     * Ответ «ok» - начислено сейчас или раньше; любой другой текст - не начислено, запись остаётся в списке конверсий.
     */
    public String payRodinaStage(String subid, String stageKey, String conversionId) {
        long telegramId;
        try {
            telegramId = Long.parseLong(String.valueOf(subid).trim());
        } catch (NumberFormatException e) {
            return "bad subaccount";
        }
        if (!"job".equals(stageKey) && !RODINA_STAGES.containsKey(stageKey)) return "unknown stage";
        AppUser found = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (found == null) return "user not found";
        var quest = questRepository.findFirstByExternalAutoApproveTrueAndActiveTrueAndExternalNetworkAndExternalOfferId("landing", "rodina").orElse(null);
        if (quest == null) {
            log.warn("[GameStage] rodina {}: no active quest, telegramId={}", stageKey, telegramId);
            return "no active quest for this offer";
        }
        Long userId = found.getId();
        StagePaid paid = tx.execute(st -> {
            AppUser user = appUserRepository.findByIdForUpdate(userId).orElseThrow();
            // ключ настройки не длиннее 64 символов, поэтому ID действия сети храним свёрнутым в UUID (36 символов)
            String idKey = conversionId == null || conversionId.isBlank() ? null : RODINA_MARK + "id."
                    + java.util.UUID.nameUUIDFromBytes(conversionId.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (idKey != null && appSettingRepository.findById(idKey).isPresent()) return StagePaid.of("ok");
            String key = stageKey;
            if ("job".equals(key)) {
                key = !paidStage(userId, "ticket") ? "ticket" : !paidStage(userId, "org") ? "org" : null;
                if (key == null) return StagePaid.of("both job steps already paid");
            }
            if (paidStage(userId, key)) return StagePaid.of("ok");
            var submission = questService.getLatestSubmission(user, quest);
            boolean closed = submission != null && submission.getStatus() == ru.gamebot.platform.domain.enums.SubmissionStatus.APPROVED;
            // квест, одобренный по прежней схеме (одна награда за «активного игрока»): этот шаг уже оплачен
            if (closed && "active".equals(key)) return StagePaid.of("ok");
            if (submission == null) submission = questService.createDraftSubmission(user, quest);
            saveMark(RODINA_MARK + key + "." + userId);
            if (idKey != null) saveMark(idKey);
            int done = (int) RODINA_STAGES.keySet().stream().filter(k -> paidStage(userId, k)).count();
            long amount = RODINA_STAGES.get(key).exc();
            if (done == RODINA_STAGES.size() && !closed) {
                questService.approveStagedSubmission(submission.getId(), amount);
                return new StagePaid("ok", key, done, submission.getId());
            }
            credit(user, amount, "Родина РП: " + RODINA_STAGES.get(key).label());
            return new StagePaid("ok", key, done, null);
        });
        if (paid.closedSubmissionId() != null) {
            eventPublisher.publishEvent(new ru.gamebot.platform.event.ExternalQuestApprovedEvent(this, paid.closedSubmissionId()));
        } else if (paid.stageKey() != null) {
            Stage stage = RODINA_STAGES.get(paid.stageKey());
            try {
                bot.caseAutoNotify(telegramId, "✅ <b>Родина РП: шаг засчитан</b>\n\n" + Character.toUpperCase(stage.label().charAt(0)) + stage.label().substring(1)
                        + "\n💰 <b>+" + rubWithKopecks(stage.exc()) + " ₽</b>: начислили " + stage.exc() + " "
                        + (sponsorBalanceService.enabled() ? "EXCASH, они выводятся в рубли без лимитов." : "EXC.")
                        + "\n\nЗасчитано шагов: " + paid.done() + " из " + RODINA_STAGES.size() + ".", null);
            } catch (Exception e) {
                log.warn("[GameStage] notice failed for {}", telegramId, e);
            }
        }
        return paid.answer();
    }

    private boolean paidStage(Long userId, String stageKey) {
        return appSettingRepository.findById(RODINA_MARK + stageKey + "." + userId).isPresent();
    }

    private void saveMark(String key) {
        AppSetting mark = new AppSetting();
        mark.setKey(key);
        mark.setValue(LocalDateTime.now().toString());
        appSettingRepository.save(mark);
    }

    private void credit(AppUser user, long amount, String reason) {
        if (sponsorBalanceService.enabled()) {
            excTransactionService.creditSponsor(user, amount, ExcTransactionService.BONUS, reason);
        } else {
            userService.addReward(user, 0, amount, ExcTransactionService.BONUS, reason);
        }
    }

    /** Разовое начисление: отметка в app_settings пишется в той же транзакции под блокировкой строки игрока. true - начислено сейчас. */
    private boolean payOnce(Long userId, String key, long amount, String reason) {
        return Boolean.TRUE.equals(tx.execute(st -> {
            AppUser user = appUserRepository.findByIdForUpdate(userId).orElseThrow();
            if (appSettingRepository.findById(key).isPresent()) return false;
            credit(user, amount, reason);
            saveMark(key);
            return true;
        }));
    }

    /** Ответ для лендинга: «ok» - начислено сейчас или раньше; любой другой текст - автовыдача не прошла, запись остаётся в списке конверсий. */
    public String paySacraliumStage1(String subid, boolean russia) {
        long telegramId;
        try {
            telegramId = Long.parseLong(String.valueOf(subid).trim());
        } catch (NumberFormatException e) {
            return "bad subaccount";
        }
        AppUser found = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (found == null) return "user not found";
        if (questRepository.findFirstByExternalAutoApproveTrueAndActiveTrueAndExternalNetworkAndExternalOfferId("landing", "sacralium").isEmpty()) {
            log.warn("[GameStage] sacralium stage 1: no active quest, telegramId={}", telegramId);
            return "no active quest for this offer";
        }
        String key = "gamestage.sacralium.1." + found.getId();
        long amount = russia ? SACRALIUM_STAGE1_RU_EXC : SACRALIUM_STAGE1_EXC;
        boolean paidNow = payOnce(found.getId(), key, amount, "Sacralium: этап 1, активный игрок");
        if (paidNow) {
            try {
                // сумма второго этапа: награда квеста, для России плюс доплата
                long secondStage = questRepository.findFirstByExternalAutoApproveTrueAndActiveTrueAndExternalNetworkAndExternalOfferId("landing", "sacralium")
                        .map(q -> q.getRewardCoins() + (russia ? RUSSIA_EXTRAS.get("sacralium").exc() : 0L)).orElse(0L);
                bot.caseAutoNotify(telegramId, "✅ <b>Sacralium: первый этап пройден</b>\n\n💰 <b>+" + rubWithKopecks(amount) + " ₽</b>: начислили " + amount + " "
                        + (sponsorBalanceService.enabled() ? "EXCASH, они выводятся в рубли без лимитов." : "EXC.")
                        + (secondStage > 0 ? "\n\nИграй дальше: за второй этап ещё <b>" + rubWithKopecks(secondStage) + " ₽</b>." : ""), null);
            } catch (Exception e) {
                log.warn("[GameStage] notice failed for {}", telegramId, e);
            }
        }
        return "ok";
    }
}
