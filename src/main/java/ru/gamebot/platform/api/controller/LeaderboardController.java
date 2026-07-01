package ru.gamebot.platform.api.controller;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.gamebot.platform.api.dto.LeaderboardEntryDto;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.service.UserService;

@RestController
@RequestMapping("/api/leaderboard")
@RequiredArgsConstructor
public class LeaderboardController {

    private final UserService userService;

    @GetMapping
    public List<LeaderboardEntryDto> leaderboard(
            @RequestParam(defaultValue = "overall") String type) {
        List<AppUser> users = "weekly".equals(type)
                ? userService.topWeekly()
                : userService.topOverall();

        List<LeaderboardEntryDto> result = new ArrayList<>();
        for (int i = 0; i < users.size(); i++) {
            AppUser u = users.get(i);
            result.add(LeaderboardEntryDto.builder()
                    .rank(i + 1)
                    .telegramId(u.getTelegramId())
                    .nickname(u.getNickname())
                    .levelName(userService.getLevelName(u.getXp()))
                    .profileTitle(u.getProfileTitle())
                    .xp(u.getXp())
                    .completedQuests(u.getCompletedQuests())
                    .build());
        }
        return result;
    }
}
