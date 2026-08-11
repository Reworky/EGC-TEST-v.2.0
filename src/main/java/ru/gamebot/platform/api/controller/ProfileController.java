package ru.gamebot.platform.api.controller;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.gamebot.platform.api.dto.UserProfileDto;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.service.RewardService;
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
    private final RewardService rewardService;

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
                            .ownedFrames(parseOwnedFrames(user.getOwnedFramesCsv()))
                            .build()))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/frame/{frameImage}")
    public ResponseEntity<?> equipFrame(@AuthenticationPrincipal Long telegramId, @PathVariable String frameImage) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        if (!rewardService.isFrameOwned(user, frameImage)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Рамка не куплена.");
        }
        // Lookup color by frame key
        java.util.Map<String, String> frameColors = java.util.Map.of(
            "fire", "#ef4444", "ice", "#38bdf8", "purple", "#a855f7",
            "gold", "#fbbf24", "egc", "#7C3AED"
        );
        user.setAvatarFrameImage(frameImage);
        user.setAvatarFrameColor(frameColors.getOrDefault(frameImage, "#7C3AED"));
        appUserRepository.save(user);
        return ResponseEntity.ok(java.util.Map.of("success", true));
    }

    private List<String> parseOwnedFrames(String csv) {
        if (csv == null || csv.isBlank()) return Collections.emptyList();
        return Arrays.asList(csv.split(","));
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
