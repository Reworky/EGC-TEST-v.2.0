package ru.gamebot.platform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.gamebot.platform.domain.enums.SubmissionStatus;
import ru.gamebot.platform.domain.model.Poll;
import ru.gamebot.platform.domain.model.QuestSubmission;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;
import ru.gamebot.platform.event.CooldownExpiredEvent;
import ru.gamebot.platform.event.PollClosedEvent;
import ru.gamebot.platform.event.QuestDeadlineWarningEvent;
import ru.gamebot.platform.event.QuestExpiredEvent;
import ru.gamebot.platform.service.TournamentService;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class WeeklyResetScheduler {

    private final UserService userService;
    private final HealthRatioService healthRatioService;
    private final PollService pollService;
    private final TournamentService tournamentService;
    private final SquadService squadService;
    private final QuestSubmissionRepository questSubmissionRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final PlatformSnapshotService platformSnapshotService;

    @Scheduled(cron = "0 0 0 * * MON")
    public void resetWeeklyLeaderboard() {
        try {
            squadService.rewardTopSquad();
        } catch (Exception e) {
            log.error("Squad weekly reward failed — continuing with XP reset", e);
        }
        try {
            userService.resetWeeklyXp();
            log.info("Weekly XP has been reset.");
        } catch (Exception e) {
            log.error("Weekly XP reset failed", e);
        }
        try {
            healthRatioService.recalculate();
        } catch (Exception e) {
            log.error("Health ratio recalculate after weekly reset failed", e);
        }
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void recalculateHealthRatio() {
        healthRatioService.recalculate();
    }

    @Scheduled(fixedDelay = 60_000)
    public void closeExpiredPolls() {
        List<Poll> expired = pollService.findExpiredUnclosed();
        for (Poll poll : expired) {
            pollService.close(poll);
            eventPublisher.publishEvent(new PollClosedEvent(this, poll));
            log.info("Poll {} closed and results published.", poll.getId());
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void processTournaments() {
        tournamentService.activateRegistrationTournaments();
        tournamentService.settleFinishedTournaments();
    }

    // Проверяем каждые 5 минут, у кого истёк кулдаун (24ч обычный / 336ч сложный)
    @Scheduled(fixedDelay = 300_000)
    public void notifyCooldownExpired() {
        LocalDateTime now = LocalDateTime.now();
        // 24ч — обычные квесты (все кроме «Сложные»)
        notifyExpired(questSubmissionRepository.findUsersWhoseNormalQuestCooldownExpiredBetween(
                now.minusHours(24).minusMinutes(5), now.minusHours(24)));
        // 336ч — только «Сложные»
        notifyExpired(questSubmissionRepository.findUsersWhoseHardQuestCooldownExpiredBetween(
                now.minusHours(336).minusMinutes(5), now.minusHours(336)));
    }

    // Автоотмена просроченных заявок — каждый час
    @Scheduled(fixedDelay = 3_600_000)
    public void cancelExpiredSubmissions() {
        List<QuestSubmission> expired = questSubmissionRepository.findExpiredActive();
        for (QuestSubmission s : expired) {
            try {
                s.setStatus(SubmissionStatus.CANCELLED);
                s.setUpdatedAt(LocalDateTime.now());
                questSubmissionRepository.save(s);
                eventPublisher.publishEvent(new QuestExpiredEvent(this,
                        s.getUser().getTelegramId(), s.getQuest().getTitle()));
            } catch (Exception e) {
                log.warn("Failed to auto-cancel expired submission {}", s.getId(), e);
            }
        }
        if (!expired.isEmpty()) {
            log.info("Auto-cancelled {} expired quest submission(s)", expired.size());
        }
    }

    // Предупреждение о дедлайне за 2 часа — каждые 10 минут
    @Scheduled(fixedDelay = 600_000)
    public void warnApproachingDeadlines() {
        LocalDateTime twoHoursFromNow = LocalDateTime.now().plusMinutes(120);
        List<QuestSubmission> expiring = questSubmissionRepository.findExpiringBefore(twoHoursFromNow);
        for (QuestSubmission s : expiring) {
            try {
                long minutesLeft = ChronoUnit.MINUTES.between(LocalDateTime.now(), s.getExpiresAt());
                s.setDeadlineWarningSent(true);
                questSubmissionRepository.save(s);
                eventPublisher.publishEvent(new QuestDeadlineWarningEvent(this,
                        s.getUser().getTelegramId(), s.getQuest().getTitle(), minutesLeft));
            } catch (Exception e) {
                log.warn("Failed to send deadline warning for submission {}", s.getId(), e);
            }
        }
    }

    // Снапшот платформы — каждый день в 00:05 (после еженедельного сброса в 00:00 в понедельник)
    @Scheduled(cron = "0 5 0 * * *")
    public void takeDailyPlatformSnapshot() {
        try {
            platformSnapshotService.takeSnapshot();
        } catch (Exception e) {
            log.warn("Daily platform snapshot failed", e);
        }
    }

    private void notifyExpired(List<Object[]> rows) {
        for (Object[] row : rows) {
            try {
                Long telegramId = (Long) row[0];
                String gameName = (String) row[1];
                String questTitle = (String) row[2];
                eventPublisher.publishEvent(new CooldownExpiredEvent(this, telegramId, gameName, questTitle));
            } catch (Exception e) {
                log.warn("Failed to send cooldown notification to user {}", row[0], e);
            }
        }
    }
}
