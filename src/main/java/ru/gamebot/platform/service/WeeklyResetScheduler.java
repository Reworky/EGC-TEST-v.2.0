package ru.gamebot.platform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WeeklyResetScheduler {

    private final UserService userService;

    @Scheduled(cron = "0 0 0 * * MON")
    public void resetWeeklyLeaderboard() {
        userService.resetWeeklyXp();
        log.info("Weekly XP has been reset.");
    }
}
