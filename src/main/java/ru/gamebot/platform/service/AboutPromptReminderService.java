package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.event.AboutPromptEvent;

/** Одно напоминание про вопросы «возраст и страна» (10.11.2026, владелец): игроку, которому вопрос показали после первого квеста 2-7 суток назад, а он не ответил.
 *  Бэклога нет по построению: в выборку попадают только те, у кого есть отметка aboutPromptedAt (её ставит QuestService после этого релиза), окно - 7 суток, за один запуск не больше 100 человек,
 *  шлём только днём (10:00-22:00 по Москве) и через общий лимит уведомлений. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AboutPromptReminderService {

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final int MAX_PER_RUN = 100;

    private final AppUserRepository appUserRepository;
    private final NotificationGateService notificationGate;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 420_000)
    public void sendReminders() {
        int hour = ZonedDateTime.now(MSK).getHour();
        if (hour < 10 || hour >= 22) return;
        LocalDateTime now = LocalDateTime.now();
        List<AppUser> due = appUserRepository.findAboutRemindDue(now.minusDays(7), now.minusDays(2));
        int sent = 0;
        for (AppUser user : due) {
            if (sent >= MAX_PER_RUN) break;
            try {
                if (!notificationGate.tryAcquire(user, NudgeType.ABOUT_PROMPT)) continue; // до отметки: повтор на следующем запуске
                appUserRepository.markAboutReminded(user.getId(), now);
                eventPublisher.publishEvent(new AboutPromptEvent(this, user.getTelegramId(), true));
                sent++;
            } catch (Exception e) {
                log.warn("Failed to send about-prompt reminder to user {}", user.getId(), e);
            }
        }
    }
}
