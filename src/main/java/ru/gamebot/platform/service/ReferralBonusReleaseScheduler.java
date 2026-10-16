package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.event.AnalyticsAlertEvent;

/** Раз в 10 минут выплачивает удержанные реферальные бонусы (приглашённому +3 000 EXC, пригласившему +2 500 EXC и дни Pass), когда приглашённый
 *  выполнил нужное число квестов и прошло нужное число дней с регистрации (2026-10-16, защита от мультиаккаунтов: бонус не на первом квесте).
 *  Порциями по 50: на момент выкладки удержанных почти нет (старые бонусы выплачены сразу), залпа сообщений не будет. Каждый игрок - в своей транзакции;
 *  сбой выплаты не теряет бонус (транзакция откатывается, флаг «удержан» остаётся) и один раз за запуск приложения шлёт алерт админам. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReferralBonusReleaseScheduler {

    private static final int BATCH = 50;

    private final AppUserRepository appUserRepository;
    private final UserService userService;
    private final ApplicationEventPublisher eventPublisher;

    private final Set<Long> alerted = ConcurrentHashMap.newKeySet();

    @Scheduled(fixedDelay = 600_000, initialDelay = 180_000)
    public void releaseDue() {
        try {
            List<AppUser> due = appUserRepository
                    .findAllByReferralFirstQuestBonusPendingTrueAndRegistrationCompletedTrueAndCompletedQuestsGreaterThanEqualAndCreatedAtBefore(
                            UserService.REFERRAL_BONUS_MIN_QUESTS, LocalDateTime.now().minusDays(UserService.REFERRAL_BONUS_MIN_AGE_DAYS));
            int processed = 0;
            for (AppUser u : due) {
                if (processed++ >= BATCH) break;
                try {
                    userService.releaseReferralBonusIfDue(u.getId());
                } catch (Exception e) {
                    log.error("Referral bonus release failed for user {}", u.getTelegramId(), e);
                    if (alerted.add(u.getId())) {
                        eventPublisher.publishEvent(new AnalyticsAlertEvent(this,
                                "🚨 <b>Не выплачен реферальный бонус</b>\n\nИгрок <code>" + u.getTelegramId() + "</code>: выплата не прошла, бонус остаётся удержанным и будет "
                                        + "повторяться каждые 10 минут. Смотрите лог бота (Referral bonus release failed)."));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Referral bonus release run failed", e);
        }
    }
}
