package ru.gamebot.platform.api.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.service.UserService;

/** Реклама AdsGram в Mini App (TMA Reward-блок, SDK на клиенте). Сама награда начисляется через
 * {@link PostbackController#adsgramReward} — этот контроллер только выставляет "ожидающий показ"
 * перед вызовом SDK, по аналогии с ботом (см. {@link UserService#markAdRequested}). */
@RestController
@RequestMapping("/api/ads")
@RequiredArgsConstructor
public class AdController {

    private final UserService userService;
    private final AppUserRepository appUserRepository;

    @GetMapping("/status")
    public ResponseEntity<AdStatusDto> status(@AuthenticationPrincipal Long telegramId) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElseThrow();
        return ResponseEntity.ok(new AdStatusDto(userService.getAdRewardsRemainingToday(user), userService.getAdRewardDailyCap()));
    }

    @PostMapping("/watch")
    public ResponseEntity<AdStatusDto> watch(@AuthenticationPrincipal Long telegramId) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElseThrow();
        int remaining = userService.getAdRewardsRemainingToday(user);
        if (remaining <= 0) {
            return ResponseEntity.status(409).body(new AdStatusDto(0, userService.getAdRewardDailyCap()));
        }
        userService.markAdRequested(user);
        return ResponseEntity.ok(new AdStatusDto(remaining, userService.getAdRewardDailyCap()));
    }

    record AdStatusDto(int remainingToday, int dailyCap) {}
}
