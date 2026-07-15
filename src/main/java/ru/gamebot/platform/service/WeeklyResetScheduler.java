package ru.gamebot.platform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.gamebot.platform.domain.model.Poll;
import ru.gamebot.platform.event.PollClosedEvent;
import ru.gamebot.platform.service.TournamentService;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class WeeklyResetScheduler {

    private final UserService userService;
    private final HealthRatioService healthRatioService;
    private final PollService pollService;
    private final TournamentService tournamentService;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(cron = "0 0 0 * * MON")
    public void resetWeeklyLeaderboard() {
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
}
