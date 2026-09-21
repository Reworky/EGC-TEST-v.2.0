package ru.gamebot.platform.api.controller;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.gamebot.platform.bot.GamePlatformBot;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.GemPurchaseRequest;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.service.ExchangeRateService;
import ru.gamebot.platform.service.GemPurchaseService;
import ru.gamebot.platform.service.GemPurchaseService.GemPackage;
import ru.gamebot.platform.service.SinkShopService;

/** «Донат по играм» для мини-аппа — зеркало флоу из бота (GamePlatformBot.sendGemPackageList /
 *  startGemPurchase / sendGemPaymentMethodChoice / requestGemPurchaseTon / sendGemStarsInvoice):
 *  каталог с ценами игрока (EGC Pass — закупочная цена), оплата Stars (ссылка на инвойс) или
 *  GRAM (TON) (заявка, модератор пишет игроку сам). Тег игры привязывается только в боте — если его
 *  нет, каталог отдаёт tagLinked=false и мини-апп уводит в бота (тот же паттерн, что у квестов). */
@Slf4j
@RestController
@RequestMapping("/api/donate")
@RequiredArgsConstructor
public class DonateController {

    /** Тот же текст-предупреждение, что GamePlatformBot.GEM_PURCHASE_ACCOUNT_ACCESS_WARNING (без HTML). */
    private static final String ACCOUNT_ACCESS_WARNING =
            "Для зачисления поставщик (топап-сервис) запросит доступ к вашему игровому аккаунту "
                    + "(email и/или код входа Supercell ID) — это требование поставщика, не самого бота. "
                    + "Все данные модератор запросит лично в переписке, нигде в приложении вводить их не нужно.";

    private final AppUserRepository appUserRepository;
    private final SinkShopService sinkShopService;
    private final ExchangeRateService exchangeRateService;
    private final GemPurchaseService gemPurchaseService;
    private final GamePlatformBot gamePlatformBot;

    @Data
    public static class PackageDto {
        private String key;
        private String label;
        /** true — не валюта, а пропуск/подписка (label != null у GemPackage). */
        private boolean pass;
        private long priceRub;
        /** Обычная цена — для зачёркивания, когда у игрока EGC Pass и priceRub = закупочная. */
        private long regularPriceRub;
        private long xpBonus;
        private int starsPrice;
        private BigDecimal tonAmount;
    }

    @Data
    public static class GameDto {
        private String gameKey;
        private String name;
        private boolean tagLinked;
        private String tag;
        /** start-параметр бота для привязки тега (brawltag/crtag/clashtag) — см. GamePlatformBot.handleStart. */
        private String tagStartParam;
        private List<PackageDto> packages = new ArrayList<>();
    }

    @Data
    public static class CatalogDto {
        private boolean egcPass;
        private String warning;
        private List<GameDto> games = new ArrayList<>();
    }

    @Data
    public static class PurchaseRequest {
        private String gameKey;
        private String packageKey;
        /** STARS | TON */
        private String method;
    }

    @Data
    public static class PurchaseResponse {
        private boolean success;
        private String message;
        /** Stars: ссылка для Telegram.WebApp.openInvoice(). */
        private String invoiceUrl;
        /** TON: номер заявки и ссылка «Написать менеджеру». */
        private Long requestDisplayId;
        private String managerUrl;

        static PurchaseResponse error(String message) {
            PurchaseResponse r = new PurchaseResponse();
            r.setSuccess(false);
            r.setMessage(message);
            return r;
        }
    }

    @GetMapping("/catalog")
    public ResponseEntity<CatalogDto> catalog(@AuthenticationPrincipal Long telegramId) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        CatalogDto dto = new CatalogDto();
        dto.setEgcPass(sinkShopService.isEgcPassActive(user));
        dto.setWarning(ACCOUNT_ACCESS_WARNING);
        // LinkedHashMap-порядок каталога = порядок в боте (Brawl Stars → Clash Royale → Clash of Clans).
        for (String gameKey : GemPurchaseService.donationGameKeys()) {
            GameDto game = new GameDto();
            game.setGameKey(gameKey);
            game.setName(GemPurchaseService.gameName(gameKey));
            String tag = gamePlatformBot.gemPurchaseGameTag(user, gameKey);
            game.setTagLinked(tag != null && !tag.isBlank());
            game.setTag(game.isTagLinked() ? tag : null);
            game.setTagStartParam(switch (gameKey) {
                case "clash_royale" -> "crtag";
                case "clash_of_clans" -> "clashtag";
                default -> "brawltag";
            });
            for (GemPackage pkg : GemPurchaseService.packagesFor(gameKey)) {
                long price = gamePlatformBot.gemPurchasePriceFor(user, pkg);
                PackageDto p = new PackageDto();
                p.setKey(pkg.key());
                p.setLabel(pkg.displayLabel());
                p.setPass(pkg.label() != null);
                p.setPriceRub(price);
                p.setRegularPriceRub(pkg.priceRub());
                p.setXpBonus(pkg.xpBonus());
                p.setStarsPrice(gamePlatformBot.gemPurchaseStarsPrice(price));
                p.setTonAmount(exchangeRateService.rubToTon(BigDecimal.valueOf(price)));
                game.getPackages().add(p);
            }
            dto.getGames().add(game);
        }
        return ResponseEntity.ok(dto);
    }

    @PostMapping("/purchase")
    public ResponseEntity<PurchaseResponse> purchase(@AuthenticationPrincipal Long telegramId, @RequestBody PurchaseRequest body) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (body == null || body.getGameKey() == null || body.getPackageKey() == null || body.getMethod() == null) {
            return ResponseEntity.ok(PurchaseResponse.error("Не хватает данных заказа."));
        }
        Optional<GemPackage> pkgOpt = gemPurchaseService.findPackage(body.getGameKey(), body.getPackageKey());
        if (pkgOpt.isEmpty()) {
            return ResponseEntity.ok(PurchaseResponse.error("Пакет не найден, выберите заново."));
        }
        GemPackage pkg = pkgOpt.get();
        String tag = gamePlatformBot.gemPurchaseGameTag(user, body.getGameKey());
        if (tag == null || tag.isBlank()) {
            return ResponseEntity.ok(PurchaseResponse.error("Сначала привяжите тег игры."));
        }

        try {
            switch (body.getMethod()) {
                case "STARS" -> {
                    String url = gamePlatformBot.createGemStarsInvoiceLink(user, body.getGameKey(), pkg);
                    if (url == null) {
                        return ResponseEntity.ok(PurchaseResponse.error("Не удалось создать счёт. Попробуйте ещё раз позже."));
                    }
                    PurchaseResponse r = new PurchaseResponse();
                    r.setSuccess(true);
                    r.setInvoiceUrl(url);
                    return ResponseEntity.ok(r);
                }
                case "TON" -> {
                    GemPurchaseRequest req = gamePlatformBot.createGemTonRequestForMiniApp(user, body.getGameKey(), pkg);
                    if (req == null) {
                        return ResponseEntity.ok(PurchaseResponse.error("Не удалось создать заявку. Мы уже знаем об ошибке — попробуйте позже."));
                    }
                    PurchaseResponse r = new PurchaseResponse();
                    r.setSuccess(true);
                    r.setRequestDisplayId(req.getDisplayId());
                    r.setManagerUrl(gamePlatformBot.gemManagerDmLinkFor(req, pkg));
                    r.setMessage("Заявка Д-" + req.getDisplayId() + " создана. Модератор свяжется с вами в личных сообщениях.");
                    return ResponseEntity.ok(r);
                }
                default -> {
                    return ResponseEntity.ok(PurchaseResponse.error("Неизвестный способ оплаты."));
                }
            }
        } catch (Exception e) {
            log.error("Donate purchase failed for user {} ({}:{} {})", telegramId, body.getGameKey(), body.getPackageKey(), body.getMethod(), e);
            return ResponseEntity.ok(PurchaseResponse.error("Ошибка при оформлении. Попробуйте ещё раз позже."));
        }
    }
}
