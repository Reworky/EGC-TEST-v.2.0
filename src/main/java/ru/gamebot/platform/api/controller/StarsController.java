package ru.gamebot.platform.api.controller;

import java.util.Arrays;
import java.util.Map;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.gamebot.platform.bot.GamePlatformBot;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.repository.AppUserRepository;

/** Ссылки на Stars-инвойсы для мини-аппа (Telegram.WebApp.openInvoice) — каталог товаров и сама
 * отправка/подтверждение платежа живут в GamePlatformBot (см. STARS_ITEMS, createStarsInvoiceLink,
 * handleSuccessfulPayment) — здесь только тонкая обёртка + проверки владения перед созданием счёта. */
@RestController
@RequestMapping("/api/stars")
@RequiredArgsConstructor
public class StarsController {

    private final AppUserRepository appUserRepository;
    private final GamePlatformBot gamePlatformBot;

    @Data
    public static class InvoiceLinkResponse {
        private boolean success;
        private String message;
        private String url;
    }

    /** Единый источник правды по ценам (2026-09-19) — мини-апп раньше хранил цены захардкоженными
     * JS-константами (ShopPage/WalletPage), отдельно от каталога STARS_ITEMS в GamePlatformBot,
     * без ничего, что держало бы их в синхроне. Как и остальной /api/stars/**, требует авторизации —
     * не проблема, мини-апп логинится (authMiniApp) раньше, чем успевает отрисовать магазин. */
    @GetMapping("/prices")
    public ResponseEntity<Map<String, Integer>> prices() {
        return ResponseEntity.ok(gamePlatformBot.getStarsItemPrices());
    }

    @PostMapping("/invoice/{itemType}")
    public ResponseEntity<InvoiceLinkResponse> invoice(@AuthenticationPrincipal Long telegramId, @PathVariable String itemType) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if ("AVATAR_FRAME".equals(itemType) && user.getOwnedFramesCsv() != null
                && Arrays.asList(user.getOwnedFramesCsv().split(",")).contains("egc")) {
            return ResponseEntity.ok(error("Рамка уже куплена."));
        }
        if ("PATRON_TITLE".equals(itemType) && user.getOwnedTitlesCsv() != null
                && Arrays.asList(user.getOwnedTitlesCsv().split(",")).contains("patron")) {
            return ResponseEntity.ok(error("Титул уже куплен."));
        }
        if ("PERMANENT_SLOT".equals(itemType) && user.isPermanentExtraSlot()) {
            return ResponseEntity.ok(error("Доп. слот навсегда уже куплен."));
        }
        // Цена зависит от длины потерянной серии (GamePlatformBot.streakRestorePriceStars) и считается
        // на сервере при создании счёта (createStreakRestoreInvoiceLink) — каталожная цена STARS_ITEMS
        // для этого товара лишь заглушка, поэтому общий путь ниже (статичная цена по itemType) для него
        // закрыт: иначе любой мог бы восстановить даже 90-дневную серию по цене 2-6-дневной.
        if ("STREAK_RESTORE".equals(itemType)) {
            String restoreUrl = gamePlatformBot.createStreakRestoreInvoiceLink(user);
            if (restoreUrl == null) {
                return ResponseEntity.ok(error("Восстанавливать нечего — предложение уже неактуально."));
            }
            InvoiceLinkResponse res = new InvoiceLinkResponse();
            res.setSuccess(true);
            res.setUrl(restoreUrl);
            return ResponseEntity.ok(res);
        }
        String url = gamePlatformBot.createStarsInvoiceLink("starsitem:" + itemType);
        if (url == null) {
            return ResponseEntity.ok(error("Не удалось создать счёт. Попробуйте ещё раз позже."));
        }
        InvoiceLinkResponse res = new InvoiceLinkResponse();
        res.setSuccess(true);
        res.setUrl(url);
        return ResponseEntity.ok(res);
    }

    private InvoiceLinkResponse error(String message) {
        InvoiceLinkResponse res = new InvoiceLinkResponse();
        res.setSuccess(false);
        res.setMessage(message);
        return res;
    }
}
