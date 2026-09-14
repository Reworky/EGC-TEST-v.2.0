package ru.gamebot.platform.api.controller;

import java.util.Arrays;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
