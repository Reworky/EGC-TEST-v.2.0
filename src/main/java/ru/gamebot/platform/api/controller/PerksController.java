package ru.gamebot.platform.api.controller;

import java.time.format.DateTimeFormatter;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.gamebot.platform.api.dto.PerkStateDto;
import ru.gamebot.platform.api.dto.ShopActionResponseDto;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.service.SinkShopService;
import ru.gamebot.platform.service.UserService;

@RestController
@RequestMapping("/api/perks")
@RequiredArgsConstructor
public class PerksController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private final SinkShopService sinkShopService;
    private final AppUserRepository appUserRepository;
    private final UserService userService;

    @Data
    public static class PurchaseRequest {
        private String key;
    }

    @Data
    public static class GiftRequest {
        private String nickname;
    }

    @GetMapping
    public ResponseEntity<PerkStateDto> state(@AuthenticationPrincipal Long telegramId) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(PerkStateDto.builder()
                .coins(user.getCoins())
                .profileTitle(user.getProfileTitle())
                .xpBoostActive(sinkShopService.isXpBoostActive(user))
                .xpBoostUntil(user.getXpBoostActiveUntil() != null ? user.getXpBoostActiveUntil().format(FMT) : null)
                .excBoostActive(sinkShopService.isExcBoostActive(user))
                .excBoostUntil(user.getExcBoostActiveUntil() != null ? user.getExcBoostActiveUntil().format(FMT) : null)
                .insuranceActive(user.isRetryInsuranceActive())
                .extraSlotActive(sinkShopService.hasExtraSlot(user))
                .extraSlotUntil(user.getQuestSlotExtraUntil() != null ? user.getQuestSlotExtraUntil().format(FMT) : null)
                .cooldownBypassActive(user.getCooldownBypassGame() != null)
                .build());
    }

    @PostMapping("/purchase")
    public ResponseEntity<ShopActionResponseDto> purchase(
            @AuthenticationPrincipal Long telegramId, @RequestBody PurchaseRequest body) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        String key = body.getKey() != null ? body.getKey() : "";
        try {
            String message = switch (key) {
                case "reroll" -> {
                    sinkShopService.purchaseReroll(user);
                    yield "Реролл активирован. Перейдите в раздел квестов — там уже другой набор заданий.";
                }
                case "insurance" -> {
                    sinkShopService.purchaseInsurance(user);
                    yield "Страховка активирована. Если следующий отчёт отклонят — сможете отправить его повторно без штрафа.";
                }
                case "extraslot" -> {
                    sinkShopService.purchaseExtraSlot(user);
                    yield "Доп. слот активирован! Теперь можно вести 3 квеста одновременно в течение 48 часов.";
                }
                case "cooldown" -> {
                    sinkShopService.purchaseCooldownRemoval(user);
                    yield "Снятие кулдауна активировано! Следующий квест с кулдауном будет доступен без ожидания.";
                }
                case "xpboost24" -> {
                    sinkShopService.purchaseXpBoost(user, 24);
                    yield "XP-буст активирован! +20% к XP за все квесты в течение 24 часов.";
                }
                case "xpboost72" -> {
                    sinkShopService.purchaseXpBoost(user, 72);
                    yield "XP-буст активирован! +20% к XP за все квесты в течение 72 часов.";
                }
                case "excboost24" -> {
                    sinkShopService.purchaseExcBoostTimed(user, 24);
                    yield "EXC-буст активирован! +20% к EXC за все квесты в течение 24 часов.";
                }
                case "excboost72" -> {
                    sinkShopService.purchaseExcBoostTimed(user, 72);
                    yield "EXC-буст активирован! +20% к EXC за все квесты в течение 72 часов.";
                }
                case "doubleboost24" -> {
                    sinkShopService.purchaseDoubleBoost(user, 24);
                    yield "Двойной буст активирован! +20% к XP и +20% к EXC за все квесты в течение 24 часов.";
                }
                case "title_basic" -> {
                    sinkShopService.purchaseTitle(user, "Новый игрок", SinkShopService.PRICE_TITLE_BASIC);
                    yield "Титул «Новый игрок» получен!";
                }
                case "title_rare" -> {
                    sinkShopService.purchaseTitle(user, "Квест-хантер", SinkShopService.PRICE_TITLE_RARE);
                    yield "Титул «Квест-хантер» получен!";
                }
                case "title_epic" -> {
                    sinkShopService.purchaseTitle(user, "Элита клуба", SinkShopService.PRICE_TITLE_EPIC);
                    yield "Титул «Элита клуба» получен!";
                }
                default -> throw new IllegalArgumentException("Неизвестный предмет.");
            };
            return ResponseEntity.ok(ShopActionResponseDto.builder().success(true).message(message).build());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.ok(ShopActionResponseDto.builder().success(false).message(e.getMessage()).build());
        }
    }

    @PostMapping("/gift")
    public ResponseEntity<ShopActionResponseDto> gift(
            @AuthenticationPrincipal Long telegramId, @RequestBody GiftRequest body) {
        AppUser sender = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (sender == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        String nickname = body.getNickname() != null ? body.getNickname().trim() : "";
        AppUser recipient = userService.findByNickname(nickname).orElse(null);
        if (recipient == null) {
            return ResponseEntity.ok(ShopActionResponseDto.builder()
                    .success(false)
                    .message("Игрок с ником «" + nickname + "» не найден.")
                    .build());
        }
        try {
            sinkShopService.purchaseGiftBoost(sender, recipient);
            return ResponseEntity.ok(ShopActionResponseDto.builder()
                    .success(true)
                    .message("Подарок отправлен! " + recipient.getNickname() + " получил XP-буст на 24 часа.")
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ShopActionResponseDto.builder().success(false).message(e.getMessage()).build());
        }
    }
}
