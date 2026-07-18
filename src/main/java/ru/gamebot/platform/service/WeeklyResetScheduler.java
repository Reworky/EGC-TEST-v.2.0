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
    private final QuestSubmissionRepository questSubmissionRepository;
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

    // Проверяем каждые 5 минут, у кого истёк кулдаун (24ч обычный / 336ч сложный)
    @Scheduled(fixedDelay = 300_000)
    public void notifyCooldownExpired() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from24 = now.minusHours(24).minusMinutes(5);
        LocalDateTime to24   = now.minusHours(24);
        LocalDateTime from336 = now.minusHours(336).minusMinutes(5);
        LocalDateTime to336   = now.minusHours(336);

        // Игровой кулдаун (по игре в целом)
        notifyGameCooldownExpired(from24, to24);
        notifyGameCooldownExpired(from336, to336);

        // Кулдаун на конкретный квест
        notifyQuestCooldownExpired(from24, to24);
        notifyQuestCooldownExpired(from336, to336);
    }

    private void notifyGameCooldownExpired(LocalDateTime from, LocalDateTime to) {
        List<Object[]> rows = questSubmissionRepository.findUsersWhoseCooldownExpiredBetween(from, to);
        for (Object[] row : rows) {
            Long telegramId = (Long) row[0];
            String gameName = (String) row[1];
            eventPublisher.publishEvent(new CooldownExpiredEvent(this, telegramId, gameName, null));
        }
    }

    private void notifyQuestCooldownExpired(LocalDateTime from, LocalDateTime to) {
        List<Object[]> rows = questSubmissionRepository.findUsersWhoseQuestCooldownExpiredBetween(from, to);
        for (Object[] row : rows) {
            Long telegramId = (Long) row[0];
            String gameName = (String) row[1];
            String questTitle = (String) row[2];
            eventPublisher.publishEvent(new CooldownExpiredEvent(this, telegramId, gameName, questTitle));
        }
    }
}
