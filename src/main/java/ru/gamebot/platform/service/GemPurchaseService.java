package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.enums.GemPurchaseStatus;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.GemPurchaseRequest;
import ru.gamebot.platform.domain.repository.GemPurchaseRequestRepository;

/** Донат по играм — покупка внутриигровой валюты за реальные деньги, вручную (см. заметки сессии
 *  2026-09-18): игрок платит переводом вне бота, админ вручную закупает на топап-сервисе и отмечает
 *  заявку выполненной. Поставщик — Купикод (kupikod, смена с donatov.net 2026-09-20: donatov.net
 *  перешёл на закупку ТОЛЬКО с входом в аккаунт игрока — неприемлемо на старте проекта, доверия ещё
 *  нет; Купикод доставляет без входа, только по тегу/ID, как и было у нас реализовано изначально).
 *  Цены и XP-бонус — снимок реальных цен Купикод на 2026-09-20 + 18% наценка (тот же принцип, что и
 *  раньше с donatov.net); бонус XP откалиброван так, чтобы дойти до 75 000 XP (потолок лимита вывода —
 *  дальше ранги чисто косметические, см. SinkShopService.getMonthlyLimit) стоило ~110 000₽ независимо
 *  от того, каким пакетом набирать — иначе дешёвые пакеты выглядели бы бесполезными, а самый крупный
 *  никто не покупает.
 *  Только Brawl Stars на старте — gameName в заявке уже общее поле под другие игры позже. */
@Service
@RequiredArgsConstructor
public class GemPurchaseService {

    /** label — необязательное явное название товара для НЕ-валютных позиций (например, "Brawl Pass",
     *  сезонный пропуск) — если задано, используется во всех текстах вместо "N гемов" (см. displayLabel(),
     *  добавлено 2026-09-20 при расширении доната за пределы просто гемов). У валютных пакетов gems>0,
     *  label=null. У не-валютных — gems=0, label задан. */
    public record GemPackage(String key, int gems, long priceRub, long xpBonus, String label) {
        public GemPackage(String key, int gems, long priceRub, long xpBonus) {
            this(key, gems, priceRub, xpBonus, null);
        }

        public String displayLabel() {
            return label != null ? label : gems + " гемов";
        }
    }

    public static final String BRAWL_STARS = "Brawl Stars";

    // Цены — розница Купикод на 2026-09-20 + 18% наценка (та же формула, что раньше с donatov.net,
    // см. класс-javadoc). XP-бонус — по калибровке ~0.68 XP/₽ (см. javadoc). Каталог расширен с 8 до
    // 15 номиналов — у Купикод их заметно больше, чем было у donatov.net (запрошено пользователем).
    public static final List<GemPackage> BRAWL_PACKAGES = List.of(
            new GemPackage("30", 30, 299, 203),
            new GemPackage("60", 60, 565, 384),
            new GemPackage("80", 80, 649, 441),
            new GemPackage("110", 110, 951, 647),
            new GemPackage("170", 170, 1_320, 898),
            new GemPackage("200", 200, 1_617, 1_100),
            new GemPackage("250", 250, 2_004, 1_363),
            new GemPackage("360", 360, 2_590, 1_761),
            new GemPackage("440", 440, 3_313, 2_253),
            new GemPackage("530", 530, 4_062, 2_762),
            new GemPackage("950", 950, 7_152, 4_863),
            new GemPackage("1310", 1_310, 9_025, 6_137),
            new GemPackage("2000", 2_000, 12_254, 8_333),
            new GemPackage("4000", 4_000, 25_620, 17_422),
            new GemPackage("6000", 6_000, 38_413, 26_121),
            // Сезонные пропуски (не валюта, gems=0) — та же формула (розница Купикод + 18%, XP по
            // ~0.68 XP/₽), см. project_economic_model. Цена у Купикод плавает (например, есть скидки
            // на некоторые номиналы) — снимок на 2026-09-20, сверять периодически.
            new GemPackage("brawlpass", 0, 1_169, 795, "Brawl Pass"),
            new GemPackage("brawlpassplus", 0, 1_564, 1_064, "Brawl Pass +Plus")
    );

    private final GemPurchaseRequestRepository repository;
    private final UserService userService;

    public Optional<GemPackage> findPackage(String key) {
        return BRAWL_PACKAGES.stream().filter(p -> p.key().equals(key)).findFirst();
    }

    /** GRAM (TON) — заявка создаётся сразу при выборе способа оплаты, БЕЗ кода платежа и скриншота:
     *  адрес кошелька клуба не публикуется в боте всем подряд (решение 2026-09-20) — модератор лично
     *  связывается с игроком, уточняет детали и сам проверяет оплату. См. GamePlatformBot.requestGemPurchaseTon. */
    @Transactional
    public GemPurchaseRequest createManualRequest(AppUser user, GemPackage pkg, String gameTag, String paymentMethod) {
        GemPurchaseRequest req = new GemPurchaseRequest();
        req.setDisplayId(repository.findMaxDisplayId() + 1);
        req.setUser(user);
        req.setGameName(BRAWL_STARS);
        req.setPackageKey(pkg.key());
        req.setGems(pkg.gems());
        req.setPriceRub(pkg.priceRub());
        req.setXpBonus(pkg.xpBonus());
        req.setItemLabel(pkg.label());
        req.setGameTag(gameTag);
        req.setPaymentMethod(paymentMethod);
        req.setStatus(GemPurchaseStatus.PENDING);
        req.setCreatedAt(LocalDateTime.now());
        req.setUpdatedAt(LocalDateTime.now());
        return repository.save(req);
    }

    /** Донат гемов, оплаченный Telegram Stars (2026-09-20) — заявка создаётся СРАЗУ после реального
     *  списания (см. GamePlatformBot.grantStarsPurchase) — сама оплата уже подтверждена Telegram, в
     *  отличие от createManualRequest (GRAM/TON), где оплаты ещё не было и её лично проверяет модератор.
     *  Гемы всё равно закупаются администратором вручную на топап-сервисе (см. approve), автоматизирована
     *  только оплата. */
    @Transactional
    public GemPurchaseRequest createStarsRequest(AppUser user, GemPackage pkg, String gameTag, int starsAmount, String telegramPaymentChargeId) {
        GemPurchaseRequest req = new GemPurchaseRequest();
        req.setDisplayId(repository.findMaxDisplayId() + 1);
        req.setUser(user);
        req.setGameName(BRAWL_STARS);
        req.setPackageKey(pkg.key());
        req.setGems(pkg.gems());
        req.setPriceRub(pkg.priceRub());
        req.setXpBonus(pkg.xpBonus());
        req.setItemLabel(pkg.label());
        req.setGameTag(gameTag);
        req.setPaymentMethod("STARS");
        req.setStarsAmount(starsAmount);
        req.setTelegramPaymentChargeId(telegramPaymentChargeId);
        req.setStatus(GemPurchaseStatus.PENDING);
        req.setCreatedAt(LocalDateTime.now());
        req.setUpdatedAt(LocalDateTime.now());
        return repository.save(req);
    }

    public Optional<GemPurchaseRequest> findById(Long id) {
        return repository.findWithUserById(id);
    }

    public List<GemPurchaseRequest> findPending() {
        return repository.findAllByStatusOrderByCreatedAtAsc(GemPurchaseStatus.PENDING);
    }

    public List<GemPurchaseRequest> findByUser(AppUser user) {
        return repository.findAllByUserOrderByCreatedAtDesc(user);
    }

    /** Одобрение — считается моментом РЕАЛЬНОЙ ручной закупки на топап-сервисе (администратор уже
     *  зачислил гемы на тег игрока), поэтому именно тут начисляется XP-бонус, не при создании заявки. */
    @Transactional
    public GemPurchaseRequest approve(Long requestId) {
        GemPurchaseRequest req = repository.findWithUserById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена."));
        if (req.getStatus() != GemPurchaseStatus.PENDING) {
            return req;
        }
        req.setStatus(GemPurchaseStatus.APPROVED);
        req.setUpdatedAt(LocalDateTime.now());
        repository.save(req);
        userService.addReward(req.getUser(), req.getXpBonus(), 0);
        return req;
    }

    @Transactional
    public GemPurchaseRequest reject(Long requestId, String reason) {
        GemPurchaseRequest req = repository.findWithUserById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена."));
        req.setStatus(GemPurchaseStatus.REJECTED);
        req.setRejectReason(reason);
        req.setUpdatedAt(LocalDateTime.now());
        return repository.save(req);
    }
}
