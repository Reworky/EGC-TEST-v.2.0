package ru.gamebot.platform.api.controller;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.gamebot.platform.api.dto.PurchaseRequestDto;
import ru.gamebot.platform.api.dto.RewardItemDto;
import ru.gamebot.platform.api.dto.ShopActionResponseDto;
import ru.gamebot.platform.bot.GamePlatformBot;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.RewardItem;
import ru.gamebot.platform.domain.model.RewardRequest;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.service.Cs2CaseCatalog;
import ru.gamebot.platform.service.HealthRatioService;
import ru.gamebot.platform.service.RewardService;
import ru.gamebot.platform.service.ShopLimitService;

@RestController
@RequestMapping("/api/shop")
@RequiredArgsConstructor
public class ShopController {

    private final RewardService rewardService;
    private final HealthRatioService healthRatioService;
    private final ShopLimitService shopLimitService;
    private final AppUserRepository appUserRepository;
    private final GamePlatformBot gamePlatformBot;
    private final Cs2CaseCatalog cs2CaseCatalog;

    @GetMapping("/items")
    public List<RewardItemDto> items(@AuthenticationPrincipal Long telegramId) {
        AppUser user = telegramId != null ? appUserRepository.findByTelegramId(telegramId).orElse(null) : null;
        return rewardService.findAvailableRewards().stream().map(item -> toDto(item, user)).toList();
    }

    /** Кейс CS2 для каталога мини-аппа. itemId = null, пока обмен выключен (товар неактивен): кейс виден, но купить его нельзя. */
    public record Cs2CaseDto(String key, String name, long priceExc, boolean withKey, List<String> skins, Long itemId) {}

    /** Каталог кейсов CS2: виден всем всегда (карточки, составы, цены), покупка возможна только когда товар активен (CS2_CASES_ENABLED). */
    @GetMapping("/cs2-cases")
    public List<Cs2CaseDto> cs2Cases() {
        List<RewardItem> active = rewardService.findByPurchaseGroup(Cs2CaseCatalog.GROUP);
        return cs2CaseCatalog.all().stream().map(c -> {
            RewardItem item = active.stream().filter(r -> r.getTitle().equals(c.title())).findFirst().orElse(null);
            long price = item != null ? rewardService.effectivePrice(item) : c.priceExc();
            return new Cs2CaseDto(c.key(), c.name(), price, c.withKey(), c.skins(), item != null ? item.getId() : null);
        }).toList();
    }

    /** Вывод в Telegram Stars — сознательно НЕ в findAvailableRewards()/общем каталоге (см. RewardService),
     *  доступен только через экран вывода в мини-аппе (Кошелёк), как рубли/TON. */
    @GetMapping("/withdraw/stars")
    public List<RewardItemDto> withdrawStarsItems(@AuthenticationPrincipal Long telegramId) {
        AppUser user = telegramId != null ? appUserRepository.findByTelegramId(telegramId).orElse(null) : null;
        return rewardService.findByPurchaseGroup("telegram_stars").stream().map(item -> toDto(item, user)).toList();
    }

    private RewardItemDto toDto(RewardItem item, AppUser user) {
        String statusNote = user != null ? shopLimitService.getItemStatus(user, item) : null;
        boolean locked = statusNote != null
                && (statusNote.startsWith("🔒") || statusNote.startsWith("⏳") || statusNote.startsWith("🚫"));
        return RewardItemDto.builder()
                .id(item.getId())
                .title(item.getTitle())
                .description(item.getDescription())
                .category(item.getCategory())
                .priceCoins(item.getPriceCoins())
                .effectivePrice(rewardService.effectivePrice(item))
                .statusNote(statusNote)
                .locked(locked)
                .userDataPrompt(item.getUserDataPrompt())
                .avatarFrameImage(item.getAvatarFrameImage())
                .build();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        int ratioPercent = (int) Math.round(healthRatioService.getCurrentRatio() * 100);
        return Map.of(
                "healthRatioPercent", ratioPercent,
                "payoutPoolRub", healthRatioService.getPayoutPoolRub(),
                "totalDebtExc", healthRatioService.getTotalDebtExc(),
                "baseRate", "1 000 EXC = " + (healthRatioService.getCurrentRatio() * 10 % 1 == 0
                        ? String.valueOf((long)(healthRatioService.getCurrentRatio() * 10))
                        : String.format("%.1f", healthRatioService.getCurrentRatio() * 10).replace('.', ',')) + " ₽"
        );
    }

    @PostMapping("/items/{id}/purchase")
    public ResponseEntity<ShopActionResponseDto> purchase(
            @PathVariable Long id,
            @AuthenticationPrincipal Long telegramId,
            @RequestBody(required = false) PurchaseRequestDto body) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        RewardItem item;
        try {
            item = rewardService.getRewardItem(id);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }

        String userData = body != null ? body.getUserData() : null;
        if (item.getUserDataPrompt() != null && !item.getUserDataPrompt().isBlank()
                && (userData == null || userData.isBlank())) {
            return ResponseEntity.badRequest().body(ShopActionResponseDto.builder()
                    .success(false)
                    .message("Укажите данные: " + item.getUserDataPrompt())
                    .build());
        }

        RewardRequest req;
        try {
            req = rewardService.createRewardRequest(user, item, userData);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ShopActionResponseDto.builder()
                    .success(false)
                    .message(e.getMessage())
                    .build());
        }

        if (userData != null && !userData.isBlank()) {
            req.setPayoutDetails(userData);
            rewardService.saveRequest(req);
        }

        String message;
        if (item.getAvatarFrameColor() != null) {
            message = "Рамка применена! Открой «Профиль», чтобы увидеть.";
        } else if ("Вывод".equals(item.getCategory())) {
            // Как в боте — вывод (сейчас Telegram Stars) уведомляет модераторов через тот же канал,
            // что и рубли/TON, а не общий "новая заявка в магазине" админам.
            gamePlatformBot.notifyAdminsAboutWithdrawal(user, req);
            message = "Заявка на вывод отправлена. Как только выплата будет выполнена, вы получите уведомление."
                    + rewardService.withdrawalPriorityNote(user);
        } else {
            gamePlatformBot.notifyAdminsAboutRewardRequest(user, item, userData);
            message = "Заявка отправлена. Как только выдача будет подтверждена, вы получите уведомление.";
        }
        return ResponseEntity.ok(ShopActionResponseDto.builder().success(true).message(message).build());
    }
}
