package ru.gamebot.platform.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.enums.SubmissionStatus;
import ru.gamebot.platform.domain.model.AppSetting;
import ru.gamebot.platform.domain.model.QuestSubmission;
import ru.gamebot.platform.domain.repository.AppSettingRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;
import ru.gamebot.platform.event.OtpMissionReminderEvent;

/** Напоминания по партнёрской миссии «ОТП Карта» (2026-11-04), каждое не больше одного раза на заявку (битовая маска QuestSubmission.missionRemindersSent):
 *  1) «миссия ждёт тебя» - игрок на этапе 1-2, прошло от 2 до 4 суток после «Взять»;
 *  2) «3 дня до конца окна» - карта активирована, до конца 30 дней на покупку осталось 1-3 дня, игрок ещё не нажал «Я заплатил(а)»;
 *  3) «банк мог заплатить» - отчёт одобрен, наступило 25-е число месяца после покупки (и ещё два дня после него).
 *  Окна отправки узкие нарочно (бэклог): заявка, у которой окно уже прошло, напоминания не получит. Шлюз частоты (NotificationGateService) спрашивается до отметки.
 *  Не шлём ночью (часы по времени JVM). Откат без деплоя: app_settings 'otp.mission.reminders.enabled' = false. */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtpMissionReminderService {

    public static final String SETTING_KEY = "otp.mission.reminders.enabled";
    public static final String GAME_NAME = "ОТП Банк";
    public static final int PURCHASE_WINDOW_DAYS = 30;
    static final int BIT_WAITING = 1;
    static final int BIT_DEADLINE = 2;
    static final int BIT_BANK_DAY = 4;
    /** Игрок нажал «Банк вернул мне 500 ₽» (админам ушло уведомление; повторно не шлём). */
    public static final int BIT_BANK_CLAIMED = 8;
    private static final int SEND_FROM_HOUR = 9;
    private static final int SEND_UNTIL_HOUR = 21;

    private final QuestSubmissionRepository questSubmissionRepository;
    private final AppSettingRepository appSettingRepository;
    private final NotificationGateService notificationGate;
    private final ApplicationEventPublisher eventPublisher;

    /** 25-е число месяца, следующего за днём покупки (правила акции банка: бонус с 25-го числа следующего месяца). */
    public static LocalDate bankPayoutDate(LocalDate purchaseDay) {
        return purchaseDay.plusMonths(1).withDayOfMonth(25);
    }

    @Scheduled(fixedDelay = 1_800_000, initialDelay = 180_000)
    public void sendReminders() {
        LocalDateTime now = LocalDateTime.now();
        if (now.getHour() < SEND_FROM_HOUR || now.getHour() >= SEND_UNTIL_HOUR || !enabled()) {
            return;
        }
        LocalDate today = now.toLocalDate();
        List<QuestSubmission> open = questSubmissionRepository.findPartnerMissionOpen(GAME_NAME, now.minusDays(120));
        for (QuestSubmission s : open) {
            try {
                if (s.getUser().isBlocked()) continue;
                int sent = s.getMissionRemindersSent();
                if (s.getStatus() == SubmissionStatus.DRAFT) {
                    int step = Math.max(1, s.getMissionStep());
                    LocalDate deadline = s.getCardActivatedOn() == null ? null : s.getCardActivatedOn().plusDays(PURCHASE_WINDOW_DAYS);
                    long daysLeft = deadline == null ? -1 : ChronoUnit.DAYS.between(today, deadline);
                    if (step == 2 && deadline != null && daysLeft >= 1 && daysLeft <= 3 && (sent & BIT_DEADLINE) == 0) {
                        deliver(s, NudgeType.QUEST_DEADLINE, BIT_DEADLINE, OtpMissionReminderEvent.KIND_DEADLINE, deadline);
                        continue;
                    }
                    long ageDays = s.getCreatedAt() == null ? -1 : ChronoUnit.DAYS.between(s.getCreatedAt().toLocalDate(), today);
                    if (step <= 2 && ageDays >= 2 && ageDays <= 4 && (sent & BIT_WAITING) == 0) {
                        deliver(s, NudgeType.OTP_MISSION, BIT_WAITING, OtpMissionReminderEvent.KIND_WAITING, null);
                    }
                } else if (s.getStatus() == SubmissionStatus.APPROVED && (sent & BIT_BANK_DAY) == 0) {
                    LocalDate base = s.getPurchaseReportedOn() != null ? s.getPurchaseReportedOn()
                            : (s.getUpdatedAt() != null ? s.getUpdatedAt().toLocalDate() : null);
                    if (base == null) continue;
                    LocalDate payout = bankPayoutDate(base);
                    long sincePayout = ChronoUnit.DAYS.between(payout, today);
                    if (sincePayout >= 0 && sincePayout <= 2) {
                        deliver(s, NudgeType.OTP_MISSION, BIT_BANK_DAY, OtpMissionReminderEvent.KIND_BANK_DAY, payout);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to process OTP mission reminder for submission {}", s.getId(), e);
            }
        }
    }

    private void deliver(QuestSubmission s, NudgeType type, int bit, int kind, LocalDate date) {
        if (!notificationGate.tryAcquire(s.getUser(), type)) return; // до отметки заявки: повтор на следующем тике
        s.setMissionRemindersSent(s.getMissionRemindersSent() | bit);
        questSubmissionRepository.save(s);
        eventPublisher.publishEvent(new OtpMissionReminderEvent(this, s.getUser().getTelegramId(), s.getId(), kind, date));
    }

    private boolean enabled() {
        return appSettingRepository.findById(SETTING_KEY)
                .map(AppSetting::getValue)
                .map(v -> !"false".equalsIgnoreCase(v.trim()))
                .orElse(true);
    }
}
