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

/** Пилот "донат по играм" — покупка внутриигровой валюты за реальные деньги, вручную (см. заметки
 *  сессии 2026-09-18): игрок платит переводом вне бота, админ вручную закупает на топап-сервисе и
 *  отмечает заявку выполненной. Цены и XP-бонус — снимок реальных цен donatov.net на 18.09.2026 +
 *  18% наценка; бонус XP откалиброван так, чтобы дойти до 75 000 XP (потолок лимита вывода —
 *  дальше ранги чисто косметические, см. SinkShopService.getMonthlyLimit) стоило ~110 000₽ независимо
 *  от того, каким пакетом набирать — иначе дешёвые пакеты выглядели бы бесполезными, а самый крупный
 *  никто не покупает.
 *  Только Brawl Stars на старте — gameName в заявке уже общее поле под другие игры позже. */
@Service
@RequiredArgsConstructor
public class GemPurchaseService {

    public record GemPackage(String key, int gems, long priceRub, long xpBonus) {}

    public static final String BRAWL_STARS = "Brawl Stars";

    public static final List<GemPackage> BRAWL_PACKAGES = List.of(
            new GemPackage("30", 30, 230, 155),
            new GemPackage("80", 80, 540, 370),
            new GemPackage("170", 170, 1_080, 730),
            new GemPackage("360", 360, 2_150, 1_450),
            new GemPackage("950", 950, 5_300, 3_600),
            new GemPackage("2000", 2_000, 10_500, 7_150),
            new GemPackage("4000", 4_000, 21_000, 14_300),
            new GemPackage("6000", 6_000, 31_500, 21_500)
    );

    private final GemPurchaseRequestRepository repository;
    private final UserService userService;

    public Optional<GemPackage> findPackage(String key) {
        return BRAWL_PACKAGES.stream().filter(p -> p.key().equals(key)).findFirst();
    }

    @Transactional
    public GemPurchaseRequest createRequest(AppUser user, GemPackage pkg, String gameTag) {
        GemPurchaseRequest req = new GemPurchaseRequest();
        req.setUser(user);
        req.setGameName(BRAWL_STARS);
        req.setPackageKey(pkg.key());
        req.setGems(pkg.gems());
        req.setPriceRub(pkg.priceRub());
        req.setXpBonus(pkg.xpBonus());
        req.setGameTag(gameTag);
        req.setPaymentCode(generatePaymentCode(user));
        req.setStatus(GemPurchaseStatus.PENDING);
        req.setCreatedAt(LocalDateTime.now());
        req.setUpdatedAt(LocalDateTime.now());
        return repository.save(req);
    }

    @Transactional
    public void attachProof(Long requestId, String photoFileId) {
        repository.findById(requestId).ifPresent(req -> {
            req.setPaymentProofFileId(photoFileId);
            req.setUpdatedAt(LocalDateTime.now());
            repository.save(req);
        });
    }

    public Optional<GemPurchaseRequest> findById(Long id) {
        return repository.findById(id);
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
        GemPurchaseRequest req = repository.findById(requestId)
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
        GemPurchaseRequest req = repository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена."));
        req.setStatus(GemPurchaseStatus.REJECTED);
        req.setRejectReason(reason);
        req.setUpdatedAt(LocalDateTime.now());
        return repository.save(req);
    }

    /** Короткий код для комментария к переводу — чтобы вручную сверить платёж с конкретной заявкой. */
    private String generatePaymentCode(AppUser user) {
        return "EGC-" + user.getTelegramId() % 100000 + "-" + (System.currentTimeMillis() % 100000);
    }
}
