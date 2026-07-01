package ru.gamebot.platform.api.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.gamebot.platform.api.dto.UserProfileDto;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.service.SinkShopService;
import ru.gamebot.platform.service.UserService;

@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final AppUserRepository appUserRepository;
    private final UserService userService;
    private final SinkShopService sinkShopService;

    @GetMapping
    public ResponseEntity<?> getProfile(@AuthenticationPrincipal Long telegramId) {
        return appUserRepository.findByTelegramId(telegramId)
                .filter(AppUser::isRegistrationCompleted)
                .map(user -> ResponseEntity.ok(UserProfileDto.builder()
                            .telegramId(user.getTelegramId())
                            .nickname(user.getNickname())
                            .country(user.getCountry())
                            .platformsCsv(user.getPlatformsCsv())
                            .interestsCsv(user.getInterestsCsv())
                            .profileTitle(user.getProfileTitle())
                            .xp(user.getXp())
                            .coins(user.getCoins())
                            .level(userService.getLevelNumber(user.getXp()))
                            .levelName(userService.getLevelName(user.getXp()))
                            .completedQuests(user.getCompletedQuests())
                            .streakDays(user.getStreakDays())
                            .monthlyWithdrawalLimit(sinkShopService.getMonthlyLimit(user.getXp()))
                            .remainingWithdrawalLimit(sinkShopService.getRemainingWithdrawalLimit(user))
                            .build());
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
