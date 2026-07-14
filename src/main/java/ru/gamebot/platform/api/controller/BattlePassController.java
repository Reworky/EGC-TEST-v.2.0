package ru.gamebot.platform.api.controller;

import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.gamebot.platform.api.dto.BattlePassDto;
import ru.gamebot.platform.api.dto.ShopActionResponseDto;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Season;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.service.SeasonService;

@RestController
@RequestMapping("/api/battlepass")
@RequiredArgsConstructor
public class BattlePassController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final SeasonService seasonService;
    private final AppUserRepository appUserRepository;

    @GetMapping
    public ResponseEntity<BattlePassDto> current(@AuthenticationPrincipal Long telegramId) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        boolean hasActivePass = seasonService.hasActivePass(user);
        BattlePassDto.BattlePassDtoBuilder builder = BattlePassDto.builder()
                .hasActivePass(hasActivePass)
                .passActiveUntil(hasActivePass && user.getSeasonPassActiveUntil() != null
                        ? user.getSeasonPassActiveUntil().format(FMT) : null)
                .userCoins(user.getCoins());

        return seasonService.findCurrentSeason()
                .map(season -> ResponseEntity.ok(builder
                        .hasSeason(true)
                        .seasonId(season.getId())
                        .name(season.getName())
                        .priceExc(season.getPriceExc())
                        .xpBoostPercent(season.getXpBoostPercent())
                        .startDate(season.getStartDate() != null ? season.getStartDate().format(FMT) : null)
                        .endDate(season.getEndDate() != null ? season.getEndDate().format(FMT) : null)
                        .build()))
                .orElseGet(() -> ResponseEntity.ok(builder.hasSeason(false).build()));
    }

    @PostMapping("/{id}/purchase")
    public ResponseEntity<ShopActionResponseDto> purchase(@PathVariable Long id, @AuthenticationPrincipal Long telegramId) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Season season = seasonService.findById(id).orElse(null);
        if (season == null) {
            return ResponseEntity.notFound().build();
        }
        SeasonService.PurchaseResult res = seasonService.purchase(user, season);
        return ResponseEntity.ok(ShopActionResponseDto.builder()
                .success(res.success())
                .message(res.success() ? "🎫 Battle Pass активирован!" : res.error())
                .build());
    }
}
