package ru.gamebot.platform.service;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.enums.RewardRequestStatus;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.RewardItem;
import ru.gamebot.platform.domain.model.RewardRequest;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.RewardItemRepository;
import ru.gamebot.platform.domain.repository.RewardRequestRepository;

@Service
@RequiredArgsConstructor
public class RewardService {

    private final RewardItemRepository rewardItemRepository;
    private final RewardRequestRepository rewardRequestRepository;
    private final UserService userService;
    private final SinkShopService sinkShopService;
    private final HealthRatioService healthRatioService;
    private final ShopLimitService shopLimitService;
    private final EntityManager entityManager;
    private final ExcTransactionService excTx;
    private final AppUserRepository appUserRepository;

    public List<RewardItem> findAvailableRewards() {
        return rewardItemRepository.findAllByActiveTrueOrderByPriceCoinsAsc();
    }

    public List<RewardItem> findByPurchaseGroup(String purchaseGroup) {
        return rewardItemRepository.findAllByActiveTrueAndPurchaseGroupOrderByPriceCoinsAsc(purchaseGroup);
    }

    public List<RewardItem> findComingSoon() {
        return rewardItemRepository.findAllByComingSoonTrueOrderByTitle();
    }

    public RewardItem getRewardItem(Long rewardId) {
        return rewardItemRepository.findById(rewardId)
                .orElseThrow(() -> new IllegalArgumentException("Награда не найдена."));
    }

    // 3.2 Level B: effective price adjusted by Health Ratio (worse HR → higher EXC price)
    public long effectivePrice(RewardItem item) {
        double ratio = healthRatioService.getCurrentRatio();
        if (ratio >= 1.0) {
            return item.getPriceCoins();
        }
        return Math.round(item.getPriceCoins() / ratio);
    }

    /**
     * Строка пользователя блокируется на всё время проверок лимитов ({@link AppUserRepository#findByIdForUpdate}) —
     * без этого два почти одновременных запроса могли пройти все 4 слоя лимитов до того, как первый
     * успеет сохраниться, и превысить месячный лимит/лимит группы товара на 1 покупку.
     */
    @Transactional
    public RewardRequest createRewardRequest(AppUser user, RewardItem rewardItem) {
        AppUser lockedUser = appUserRepository.findByIdForUpdate(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден."));

        // 4-layer shop limits check (throws IllegalArgumentException on violation)
        shopLimitService.checkAllLimits(lockedUser, rewardItem);

        boolean isAvatarFrame = "avatar_frame".equals(rewardItem.getPurchaseGroup());
        long price = effectivePrice(rewardItem);

        // 3.3 Withdrawal limit check (min 5000 EXC per model)
        if (price < 5_000) {
            price = rewardItem.getPriceCoins(); // small items bypass withdrawal tracking
        }

        if (lockedUser.getCoins() < price) {
            throw new IllegalArgumentException("Недостаточно EXC. Нужно " + price + " EXC.");
        }

        if (!isAvatarFrame) {
            long remaining = sinkShopService.getRemainingWithdrawalLimit(lockedUser);
            if (price > remaining) {
                throw new IllegalArgumentException(
                        "Превышен месячный лимит вывода. Доступно ещё: " + remaining + " EXC.");
            }
        }

        lockedUser.setCoins(lockedUser.getCoins() - price);
        userService.save(lockedUser);
        excTx.log(lockedUser, -price, ExcTransactionService.SHOP_BUY, "Покупка: " + rewardItem.getTitle());
        if (!isAvatarFrame) {
            // Рамки не учитываются в месячном лимите трат и не ставят cooldown — по требованию пользователя,
            // они полностью без ограничений и не должны мешать другим покупкам в магазине.
            sinkShopService.recordWithdrawal(lockedUser, price);
            shopLimitService.recordPurchaseCooldown(lockedUser, rewardItem.getPriceCoins());
        }

        RewardRequest request = new RewardRequest();
        request.setUser(lockedUser);
        request.setRewardItem(rewardItem);
        request.setCreatedAt(LocalDateTime.now());
        request.setDisplayId(rewardRequestRepository.findMaxShopDisplayId() + 1);

        if (rewardItem.getAvatarFrameColor() != null) {
            // Цифровая косметика — применяется мгновенно, без очереди на одобрение администратора
            lockedUser.setAvatarFrameColor(rewardItem.getAvatarFrameColor());
            // Картинка рамки заменяет предыдущую (в т.ч. сбрасывает её, если новая рамка — просто цвет без картинки)
            lockedUser.setAvatarFrameImage(rewardItem.getAvatarFrameImage());
            userService.save(lockedUser);
            request.setStatus(RewardRequestStatus.APPROVED);
        } else {
            request.setStatus(RewardRequestStatus.PENDING);
        }
        return rewardRequestRepository.save(request);
    }

    public List<RewardRequest> findPendingRequests() {
        List<RewardRequest> pending = rewardRequestRepository.findAllByStatusOrderByCreatedAtAsc(RewardRequestStatus.PENDING);
        List<RewardRequest> inProgress = rewardRequestRepository.findAllByStatusOrderByCreatedAtAsc(RewardRequestStatus.IN_PROGRESS);
        return java.util.stream.Stream.concat(pending.stream(), inProgress.stream())
                .filter(r -> !"Вывод".equals(r.getRewardItem().getCategory()))
                .toList();
    }

    public List<RewardRequest> findPendingWithdrawals() {
        return rewardRequestRepository.findAllByStatusAndRewardItemCategoryOrderByCreatedAtAsc(
                RewardRequestStatus.PENDING, "Вывод");
    }

    public boolean hasPendingWithdrawal(AppUser user) {
        return rewardRequestRepository.countPendingWithdrawalsByUser(user) > 0;
    }

    public boolean hasWithdrawalTodayOrPending(AppUser user) {
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        return rewardRequestRepository.countWithdrawalsByUserSince(user, since) > 0;
    }

    public long countPendingWithdrawalsByUser(AppUser user) {
        return rewardRequestRepository.countPendingWithdrawalsByUser(user);
    }

    public List<RewardRequest> findUserRequests(AppUser user) {
        return rewardRequestRepository.findAllByUserOrderByCreatedAtDesc(user);
    }

    public long countPendingRequests() {
        return rewardRequestRepository.countByStatusIn(
                java.util.List.of(RewardRequestStatus.PENDING, RewardRequestStatus.IN_PROGRESS));
    }

    public RewardRequest getRequest(Long requestId) {
        return rewardRequestRepository.findWithUserAndRewardItemById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена."));
    }

    @Transactional
    public RewardRequest takeInProgressRequest(Long requestId) {
        RewardRequest req = getRequest(requestId);
        req.setStatus(RewardRequestStatus.IN_PROGRESS);
        return rewardRequestRepository.save(req);
    }

    @Transactional
    public RewardRequest approveRequest(Long requestId) {
        RewardRequest req = getRequest(requestId);
        req.setStatus(RewardRequestStatus.APPROVED);
        // Withdrawal limit already recorded at request creation time — do NOT call recordWithdrawal again
        return rewardRequestRepository.save(req);
    }

    @Transactional
    public RewardRequest cancelRequest(Long requestId, AppUser requester) {
        RewardRequest req = getRequest(requestId);
        if (req.getStatus() != RewardRequestStatus.PENDING) {
            throw new IllegalArgumentException("Заявку можно отменить только в статусе «Ожидает».");
        }
        req.setStatus(RewardRequestStatus.CANCELLED);
        boolean isWithdrawal = "Вывод".equals(req.getRewardItem().getCategory());
        long price = isWithdrawal ? req.getRewardItem().getPriceCoins() : effectivePrice(req.getRewardItem());
        requester.setCoins(requester.getCoins() + price);
        excTx.log(requester, price, ExcTransactionService.SHOP_REFUND, "Отмена заявки: " + req.getRewardItem().getTitle());
        userService.save(requester);
        // reverseWithdrawal for ALL items since recordWithdrawal is called for all in createRewardRequest
        sinkShopService.reverseWithdrawal(requester, price);
        return rewardRequestRepository.save(req);
    }

    @Transactional
    public RewardRequest rejectRequest(Long requestId, String comment) {
        RewardRequest req = getRequest(requestId);
        req.setStatus(RewardRequestStatus.REJECTED);
        req.setAdminComment(comment);
        AppUser user = req.getUser();
        boolean isWithdrawal = "Вывод".equals(req.getRewardItem().getCategory());
        long price = isWithdrawal ? req.getRewardItem().getPriceCoins() : effectivePrice(req.getRewardItem());
        user.setCoins(user.getCoins() + price);
        excTx.log(user, price, ExcTransactionService.SHOP_REFUND, "Возврат (отклонение): " + req.getRewardItem().getTitle());
        userService.save(user);
        sinkShopService.reverseWithdrawal(user, price);
        // Отклонение — не вина игрока, поэтому снимаем cooldown по ценовому диапазону товара (Layer 4)
        if (!isWithdrawal) {
            shopLimitService.reverseCooldown(user, req.getRewardItem().getPriceCoins());
        }
        return rewardRequestRepository.save(req);
    }

    @Transactional
    public RewardRequest saveRequest(RewardRequest req) {
        return rewardRequestRepository.save(req);
    }

    public List<RewardItem> findAllRewards() {
        return rewardItemRepository.findAll();
    }

    @Transactional
    public RewardItem save(RewardItem item) {
        return rewardItemRepository.save(item);
    }

    @Transactional
    public void deleteRewardItem(Long id) {
        RewardItem item = rewardItemRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Награда не найдена."));
        rewardRequestRepository.deleteAllByRewardItem(item);
        rewardItemRepository.delete(item);
    }

    @Transactional
    public RewardItem createRewardItem(String title, String description, String category, long priceCoins) {
        return createRewardItem(title, description, category, priceCoins, null);
    }

    @Transactional
    public RewardItem createRewardItem(String title, String description, String category, long priceCoins, String photoFileId) {
        RewardItem item = new RewardItem();
        item.setTitle(title);
        item.setDescription(description);
        item.setPhotoFileId(photoFileId);
        item.setCategory(category);
        item.setPriceCoins(priceCoins);
        item.setActive(true);
        item.setCreatedAt(LocalDateTime.now());
        return rewardItemRepository.save(item);
    }

    /** Строка пользователя блокируется на всё время проверки лимита ({@link AppUserRepository#findByIdForUpdate}) —
     * та же защита от гонки состояний, что и в {@link #createRewardRequest}. */
    @Transactional
    public RewardRequest createTonWithdrawalRequest(AppUser user, long excAmount, long rubles, String tonWallet) {
        AppUser lockedUser = appUserRepository.findByIdForUpdate(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден."));

        long remaining = sinkShopService.getRemainingWithdrawalLimit(lockedUser);
        if (excAmount > remaining) {
            throw new IllegalArgumentException("Превышен месячный лимит вывода. Доступно ещё: " + remaining + " EXC.");
        }
        if (excAmount > lockedUser.getCoins()) {
            throw new IllegalArgumentException("Недостаточно EXC. Ваш баланс: " + lockedUser.getCoins() + " EXC.");
        }

        RewardItem withdrawItem = new RewardItem();
        withdrawItem.setTitle("Вывод " + excAmount + " EXC → TON");
        withdrawItem.setDescription("Заявка на вывод в TON · Кошелёк: " + tonWallet);
        withdrawItem.setCategory("Вывод");
        withdrawItem.setPriceCoins(excAmount);
        withdrawItem.setActive(false);
        withdrawItem.setCreatedAt(LocalDateTime.now());
        RewardItem saved = rewardItemRepository.save(withdrawItem);

        sinkShopService.recordWithdrawal(lockedUser, excAmount);
        lockedUser.setCoins(lockedUser.getCoins() - excAmount);
        excTx.log(lockedUser, -excAmount, ExcTransactionService.WITHDRAWAL, "Вывод → TON");
        userService.save(lockedUser);

        RewardRequest request = new RewardRequest();
        request.setUser(lockedUser);
        request.setRewardItem(saved);
        request.setStatus(RewardRequestStatus.PENDING);
        request.setCreatedAt(LocalDateTime.now());
        // Префикс "TON:" — новый формат. Старые заявки могли быть сохранены с "USDT·TON:" (до перехода на TON) —
        // код чтения (isCryptoWithdrawal/cryptoWalletFromPayoutDetails в GamePlatformBot) понимает оба варианта.
        request.setPayoutDetails("TON:" + tonWallet + ":rubles=" + rubles);
        request.setDisplayId(rewardRequestRepository.findMaxWithdrawalDisplayId() + 1);
        return rewardRequestRepository.save(request);
    }

    @Transactional
    public void resetWithdrawalRequestIds() {
        // Find all withdrawal reward items
        List<RewardItem> withdrawalItems = rewardItemRepository.findAll().stream()
                .filter(i -> "Вывод".equals(i.getCategory()))
                .toList();
        // Delete all requests referencing these items first (FK constraint)
        for (RewardItem item : withdrawalItems) {
            rewardRequestRepository.deleteAllByRewardItem(item);
        }
        // Then delete the virtual reward items
        rewardItemRepository.deleteAll(withdrawalItems);
        // Flush so H2 sees the deletes before DDL
        entityManager.flush();
        // Reset reward_request identity sequence (H2 stores table as lowercase quoted)
        entityManager.createNativeQuery(
                "ALTER TABLE REWARD_REQUESTS ALTER COLUMN id RESTART WITH 1")
                .executeUpdate();
    }

    /** Строка пользователя блокируется на всё время проверки лимита ({@link AppUserRepository#findByIdForUpdate}) —
     * та же защита от гонки состояний, что и в {@link #createRewardRequest}. Лимит/баланс проверяются здесь
     * повторно (defense-in-depth) — раньше единственная проверка была на шаге ввода суммы, ДО этого метода. */
    @Transactional
    public RewardRequest createWithdrawalRequestWithDetails(AppUser user, long excAmount, long rubles, String payoutDetails) {
        AppUser lockedUser = appUserRepository.findByIdForUpdate(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден."));

        long remaining = sinkShopService.getRemainingWithdrawalLimit(lockedUser);
        if (excAmount > remaining) {
            throw new IllegalArgumentException("Превышен месячный лимит вывода. Доступно ещё: " + remaining + " EXC.");
        }
        if (excAmount > lockedUser.getCoins()) {
            throw new IllegalArgumentException("Недостаточно EXC. Ваш баланс: " + lockedUser.getCoins() + " EXC.");
        }

        RewardItem withdrawItem = new RewardItem();
        withdrawItem.setTitle("Вывод " + excAmount + " EXC → " + rubles + " ₽");
        withdrawItem.setDescription("Заявка на вывод средств");
        withdrawItem.setCategory("Вывод");
        withdrawItem.setPriceCoins(excAmount);
        withdrawItem.setActive(false);
        withdrawItem.setCreatedAt(LocalDateTime.now());
        RewardItem saved = rewardItemRepository.save(withdrawItem);

        sinkShopService.recordWithdrawal(lockedUser, excAmount);
        lockedUser.setCoins(lockedUser.getCoins() - excAmount);
        excTx.log(lockedUser, -excAmount, ExcTransactionService.WITHDRAWAL, "Вывод → " + rubles + " ₽");
        userService.save(lockedUser);

        RewardRequest request = new RewardRequest();
        request.setUser(lockedUser);
        request.setRewardItem(saved);
        request.setStatus(RewardRequestStatus.PENDING);
        request.setPayoutDetails(payoutDetails);
        request.setCreatedAt(LocalDateTime.now());
        request.setDisplayId(rewardRequestRepository.findMaxWithdrawalDisplayId() + 1);
        return rewardRequestRepository.save(request);
    }

}
