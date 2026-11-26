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

    /** Этап 1 Sacralium: 700 EXCASH = 7 ₽ (≈30% нижней выплаты 24,60 ₽). */
    public static final long SACRALIUM_STAGE1_EXC = 700L;

    /** «Путь Судьбы»: за игрока из России сеть платит вдвое больше (120 ₽ против 60 ₽) - доплата 1 800 EXCASH = ещё 18 ₽ к награде квеста (всего 36 ₽). */
    public static final long PATHOFDOOM_RU_EXTRA_EXC = 1_800L;

    /** «Охотник на Демонов»: за игрока из России сеть платит 96 ₽ против 64 ₽ - доплата 960 EXCASH = ещё 9,60 ₽ к награде квеста (всего 28,80 ₽). */
    public static final long DEMONSLAYER_RU_EXTRA_EXC = 960L;

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
                                  @Lazy GamePlatformBot bot, PlatformTransactionManager txManager) {
        this.appUserRepository = appUserRepository;
        this.appSettingRepository = appSettingRepository;
        this.questRepository = questRepository;
        this.excTransactionService = excTransactionService;
        this.userService = userService;
        this.sponsorBalanceService = sponsorBalanceService;
        this.bot = bot;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Доплата за Россию к уже одобренному квесту «Путь Судьбы»: один раз на игрока. Ответ как у этапа: «ok» - начислено сейчас или раньше. */
    public String payPathOfDoomRussiaExtra(String subid) {
        long telegramId;
        try {
            telegramId = Long.parseLong(String.valueOf(subid).trim());
        } catch (NumberFormatException e) {
            return "bad subaccount";
        }
        AppUser found = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (found == null) return "user not found";
        if (payOnce(found.getId(), "gamestage.pathofdoom.ru." + found.getId(), PATHOFDOOM_RU_EXTRA_EXC, "Путь Судьбы: доплата за Россию")) {
            try {
                bot.caseAutoNotify(telegramId, "💰 <b>Путь Судьбы: ещё +18 ₽</b>\n\nИгрокам из России награда 36 ₽: доначислили 1 800 "
                        + (sponsorBalanceService.enabled() ? "EXCASH, они выводятся в рубли без лимитов." : "EXC."), null);
            } catch (Exception e) {
                log.warn("[GameStage] notice failed for {}", telegramId, e);
            }
        }
        return "ok";
    }

    /** Доплата за Россию к уже одобренному квесту «Охотник на Демонов»: один раз на игрока. Ответ: «ok» - начислено сейчас или раньше. */
    public String payDemonSlayerRussiaExtra(String subid) {
        long telegramId;
        try {
            telegramId = Long.parseLong(String.valueOf(subid).trim());
        } catch (NumberFormatException e) {
            return "bad subaccount";
        }
        AppUser found = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (found == null) return "user not found";
        if (payOnce(found.getId(), "gamestage.demonslayer.ru." + found.getId(), DEMONSLAYER_RU_EXTRA_EXC, "Охотник на Демонов: доплата за Россию")) {
            try {
                bot.caseAutoNotify(telegramId, "💰 <b>Охотник на Демонов: ещё +9,60 ₽</b>\n\nИгрокам из России награда 28,80 ₽: доначислили 960 "
                        + (sponsorBalanceService.enabled() ? "EXCASH, они выводятся в рубли без лимитов." : "EXC."), null);
            } catch (Exception e) {
                log.warn("[GameStage] notice failed for {}", telegramId, e);
            }
        }
        return "ok";
    }

    /** Разовое начисление: отметка в app_settings пишется в той же транзакции под блокировкой строки игрока. true - начислено сейчас. */
    private boolean payOnce(Long userId, String key, long amount, String reason) {
        return Boolean.TRUE.equals(tx.execute(st -> {
            AppUser user = appUserRepository.findByIdForUpdate(userId).orElseThrow();
            if (appSettingRepository.findById(key).isPresent()) return false;
            if (sponsorBalanceService.enabled()) {
                excTransactionService.creditSponsor(user, amount, ExcTransactionService.BONUS, reason);
            } else {
                userService.addReward(user, 0, amount, ExcTransactionService.BONUS, reason);
            }
            AppSetting mark = new AppSetting();
            mark.setKey(key);
            mark.setValue(LocalDateTime.now().toString());
            appSettingRepository.save(mark);
            return true;
        }));
    }

    /** Ответ для лендинга: «ok» - начислено сейчас или раньше; любой другой текст - автовыдача не прошла, запись остаётся в списке конверсий. */
    public String paySacraliumStage1(String subid) {
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
        boolean paidNow = payOnce(found.getId(), key, SACRALIUM_STAGE1_EXC, "Sacralium: этап 1, активный игрок");
        if (paidNow) {
            try {
                bot.caseAutoNotify(telegramId, "✅ <b>Sacralium: первый этап пройден</b>\n\n💰 <b>+7 ₽</b>: начислили 700 "
                        + (sponsorBalanceService.enabled() ? "EXCASH, они выводятся в рубли без лимитов." : "EXC.")
                        + "\n\nИграй дальше: за второй этап ещё <b>100 ₽</b>.", null);
            } catch (Exception e) {
                log.warn("[GameStage] notice failed for {}", telegramId, e);
            }
        }
        return "ok";
    }
}
