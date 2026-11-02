package ru.gamebot.platform.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.model.AppSetting;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.repository.AppSettingRepository;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.DailyCodeClaimRepository;
import ru.gamebot.platform.event.CodeHuntBonusEvent;

/** «Охота за кодами» (01.11.2026): кто за месяц поймал нужное число кодов дня (ClubActivityService.requiredForMonth, до 10), тому в начале следующего месяца
 *  разовый запас вывода сверх лимита: процент от его месячного лимита, но не меньше 5 000 EXC (ClubActivityService.monthlyBonusExc). Запас не копится от месяца к месяцу
 *  (SinkShopService.ensurePartnerWithdrawalAllowance). Месяц обрабатывается один раз (ключ в app_settings); за месяцы до MONTHLY_BONUS_FROM ничего не выдаётся,
 *  поэтому первый запуск после деплоя ничего не начислит «задним числом». */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeHuntService {

    private final ClubActivityService clubActivityService;
    private final DailyCodeClaimRepository claimRepository;
    private final AppUserRepository appUserRepository;
    private final AppSettingRepository settingRepository;
    private final SinkShopService sinkShopService;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 360_000)
    public void awardPreviousMonth() {
        try {
            awardMonth(YearMonth.now().minusMonths(1));
        } catch (Exception e) {
            log.error("Code hunt monthly award failed", e);
        }
    }

    /** Возвращает число игроков, получивших бонус; 0, если месяц уже обработан или слишком ранний. */
    public int awardMonth(YearMonth ym) {
        if (ym.isBefore(ClubActivityService.MONTHLY_BONUS_FROM)) return 0;
        String key = "codehunt.awarded." + ym;
        if (settingRepository.findById(key).isPresent()) return 0;
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();
        int required = clubActivityService.requiredForMonth(ym);
        List<Object[]> rows = claimRepository.findUsersWithClaimsInRange(from, to, required);
        int awarded = 0;
        for (Object[] row : rows) {
            try {
                Long userId = ((Number) row[0]).longValue();
                int caught = ((Number) row[1]).intValue();
                AppUser user = appUserRepository.findById(userId).orElse(null);
                if (user == null || user.isBlocked()) continue;
                long bonus = ClubActivityService.monthlyBonusExc(sinkShopService.getMonthlyLimit(user));
                sinkShopService.ensurePartnerWithdrawalAllowance(user, bonus);
                eventPublisher.publishEvent(new CodeHuntBonusEvent(this, user, caught, required, bonus, ym));
                awarded++;
            } catch (Exception e) {
                log.warn("Code hunt bonus failed for row {}", java.util.Arrays.toString(row), e);
            }
        }
        AppSetting done = new AppSetting();
        done.setKey(key);
        done.setValue(String.valueOf(awarded));
        settingRepository.save(done);
        log.info("[CodeHunt] {}: required {}, awarded {}", ym, required, awarded);
        return awarded;
    }
}
