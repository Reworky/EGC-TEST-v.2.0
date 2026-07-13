package ru.gamebot.platform.api.controller;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.gamebot.platform.api.dto.LeaderboardEntryDto;
import ru.gamebot.platform.api.dto.LeaderboardResponseDto;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.service.UserService;

@RestController
@RequestMapping("/api/leaderboard")
@RequiredArgsConstructor
public class LeaderboardController {

    private final UserService userService;

    @GetMapping
    public LeaderboardResponseDto leaderboard(
            @RequestParam(defaultValue = "overall") String type,
            @AuthenticationPrincipal Long telegramId) {
        boolean weekly = "weekly".equals(type);
        List<AppUser> users = weekly ? userService.topWeekly() : userService.topOverall();

        List<LeaderboardEntryDto> entries = new ArrayList<>();
        for (int i = 0; i < users.size(); i++) {
            entries.add(toDto(users.get(i), i + 1, weekly, false));
        }

        LeaderboardEntryDto me = null;
        if (telegramId != null) {
            AppUser myUser = userService.findByTelegramId(telegramId).orElse(null);
            if (myUser != null && myUser.isRegistrationCompleted()) {
                long rank = weekly ? userService.getWeeklyRank(myUser) : userService.getOverallRank(myUser);
                me = toDto(myUser, (int) rank, weekly, true);
            }
        }

        return LeaderboardResponseDto.builder().entries(entries).me(me).build();
    }

    private LeaderboardEntryDto toDto(AppUser u, int rank, boolean weekly, boolean includeLeague) {
        LeaderboardEntryDto.LeaderboardEntryDtoBuilder builder = LeaderboardEntryDto.builder()
                .rank(rank)
                .telegramId(u.getTelegramId())
                .nickname(u.getNickname())
                .levelName(userService.getLevelName(u.getXp()))
                .profileTitle(u.getProfileTitle())
                .xp(weekly ? u.getWeeklyXp() : u.getXp())
                .completedQuests(u.getCompletedQuests());
        if (weekly && includeLeague) {
            UserService.League league = UserService.getLeague(u.getWeeklyXp());
            builder.leagueName(league.displayName).leagueExcPrize(league.excPrize);
        }
        return builder.build();
    }
}
