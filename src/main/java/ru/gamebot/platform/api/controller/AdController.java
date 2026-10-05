package ru.gamebot.platform.api.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.service.UserService;
import ru.gamebot.platform.service.UserService.AdRewardSource;

/** Реклама в Mini App (AdsGram и Telega.io, SDK на клиенте) — каждая сеть свой AdRewardSource, свой
 * дневной лимит. Сама награда начисляется через {@link PostbackController#adsgramReward}/
 * {@link PostbackController#telegaReward} — этот контроллер только выставляет "ожидающий показ"
 * перед вызовом SDK, по аналогии с ботом (см. {@link UserService#markAdRequested}). */
@RestController
@RequestMapping("/api/ads")
@RequiredArgsConstructor
public class AdController {

    private final UserService userService;
    private final AppUserRepository appUserRepository;

    @GetMapping("/status")
    public ResponseEntity<AdStatusDto> status(@AuthenticationPrincipal Long telegramId, @RequestParam AdRewardSource source) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElseThrow();
        return ResponseEntity.ok(new AdStatusDto(userService.getAdRewardsRemainingToday(user, source), userService.getAdRewardDailyCap(source)));
    }

    @PostMapping("/watch")
    public ResponseEntity<AdStatusDto> watch(@AuthenticationPrincipal Long telegramId, @RequestParam AdRewardSource source,
                                             @RequestParam(required = false) String purpose,
                                             @RequestParam(required = false) String placement) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElseThrow();
        int remaining = userService.getAdRewardsRemainingToday(user, source);
        if (remaining <= 0) {
            return ResponseEntity.status(409).body(new AdStatusDto(0, userService.getAdRewardDailyCap(source)));
        }
        // purpose=WHEEL — награда за этот показ будет спином рекламного колеса; purpose=STREAK — право восстановить прерванную серию
        // (только если это сейчас доступно, иначе 409); любое другое значение = обычные 30 EXC
        String effectivePurpose = null;
        if (UserService.AD_PURPOSE_WHEEL.equals(purpose)) {
            effectivePurpose = purpose;
        } else if (UserService.AD_PURPOSE_STREAK.equals(purpose)) {
            if (!userService.canRestoreStreakByAd(user)) {
                return ResponseEntity.status(409).body(new AdStatusDto(0, userService.getAdRewardDailyCap(source)));
            }
            effectivePurpose = purpose;
        }
        userService.markAdRequested(user, effectivePurpose, sanitizePlacement(placement, effectivePurpose));
        return ResponseEntity.ok(new AdStatusDto(remaining, userService.getAdRewardDailyCap(source)));
    }

    /** Место показа для статистики: только известные значения, чтобы клиент не засорял таблицу произвольными строками. */
    private static String sanitizePlacement(String placement, String purpose) {
        if (UserService.AD_PURPOSE_STREAK.equals(purpose)) return "streak";
        if (UserService.AD_PURPOSE_WHEEL.equals(purpose)) return "wheel";
        return switch (placement == null ? "" : placement) {
            case "quests", "wallet" -> placement;
            default -> "other";
        };
    }

    record AdStatusDto(int remainingToday, int dailyCap) {}
}
