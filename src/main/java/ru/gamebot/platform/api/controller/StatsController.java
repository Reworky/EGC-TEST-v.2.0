package ru.gamebot.platform.api.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.gamebot.platform.api.dto.ClubStatsDto;
import ru.gamebot.platform.service.HealthRatioService;
import ru.gamebot.platform.service.QuestService;
import ru.gamebot.platform.service.UserService;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final UserService userService;
    private final QuestService questService;
    private final HealthRatioService healthRatioService;

    @GetMapping
    public ClubStatsDto stats() {
        return ClubStatsDto.builder()
                .totalPlayers(userService.totalRegisteredUsers())
                .newUsersWeek(userService.countNewUsersSince(java.time.LocalDateTime.now().minusDays(7)))
                .totalQuestsCompleted(questService.countAllApproved())
                .totalExcIssued(questService.sumAllIssuedCoins())
                .topGame(questService.topGameName())
                .healthRatioPercent((int) Math.round(healthRatioService.getCurrentRatio() * 100))
                .payoutPoolRub(healthRatioService.getPayoutPoolRub())
                .build();
    }
}
