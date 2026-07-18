package ru.gamebot.platform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.gamebot.platform.domain.model.Poll;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;
import ru.gamebot.platform.event.CooldownExpiredEvent;
import ru.gamebot.platform.event.PollClosedEvent;
import ru.gamebot.platform.service.TournamentService;

import java.time.LocalDateTime;
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

    @Scheduled(cron = "0 0 0 * * MON")
    public void resetWeeklyLeaderboard() {
        squadService.rewardTopSquad();
        userService.resetWeeklyXp();
        log.info("Weekly XP has been reset.");
        healthRatioService.recalculate();
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
        // 24ч — обычные квесты (исключаем «Сложные»)
        notifyQuestCooldownExpired(now.minusHours(24).minusMinutes(5), now.minusHours(24), "Сложные");
        // 336ч — только «Сложные» квесты
        notifyQuestCooldownExpired(now.minusHours(336).minusMinutes(5), now.minusHours(336), "");
    }

    private void notifyQuestCooldownExpired(LocalDateTime from, LocalDateTime to, String excludeCategory) {
        List<Object[]> rows;
        try {
            rows = questSubmissionRepository.findUsersWhoseQuestCooldownExpiredBetween(from, to, excludeCategory);
        } catch (Exception e) {
            log.warn("Failed to query cooldown expirations", e);
            return;
        }
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
