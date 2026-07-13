package ru.gamebot.platform.api.controller;

import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.gamebot.platform.api.dto.UserProfileDto;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.service.SinkShopService;
import ru.gamebot.platform.service.TelegramFileService;
import ru.gamebot.platform.service.UserService;

@Slf4j
@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final AppUserRepository appUserRepository;
    private final UserService userService;
    private final SinkShopService sinkShopService;
    private final TelegramFileService telegramFileService;

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
                            .hasAvatar(user.getAvatarFileId() != null)
                            .avatarFrameColor(user.getAvatarFrameColor())
                            .avatarFrameImage(user.getAvatarFrameImage())
                            .build()))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/avatar")
    public ResponseEntity<byte[]> getAvatar(@AuthenticationPrincipal Long telegramId) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null || user.getAvatarFileId() == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] image = telegramFileService.downloadFile(user.getAvatarFileId());
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)))
                    .body(image);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.notFound().build();
        } catch (IOException e) {
            log.warn("Failed to fetch avatar for user {}", telegramId, e);
            return ResponseEntity.notFound().build();
        }
    }
}
