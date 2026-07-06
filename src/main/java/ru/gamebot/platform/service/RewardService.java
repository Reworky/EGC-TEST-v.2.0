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

    @Transactional
    public RewardRequest createRewardRequest(AppUser user, RewardItem rewardItem) {
        // 4-layer shop limits check (throws IllegalArgumentException on violation)
        shopLimitService.checkAllLimits(user, rewardItem);

        long price = effectivePrice(rewardItem);

        // 3.3 Withdrawal limit check (min 5000 EXC per model)
        if (price < 5_000) {
            price = rewardItem.getPriceCoins(); // small items bypass withdrawal tracking
        }

        if (user.getCoins() < price) {
            throw new IllegalArgumentException("Недостаточно EXC. Нужно " + price + " EXC.");
        }

        long remaining = sinkShopService.getRemainingWithdrawalLimit(user);
        if (price > remaining) {
            throw new IllegalArgumentException(
                    "Превышен месячный лимит вывода. Доступно ещё: " + remaining + " EXC.");
        }

        user.setCoins(user.getCoins() - price);
        userService.save(user);
        sinkShopService.recordWithdrawal(user, price);

        // Record cooldown after successful purchase (Layer 4)
        shopLimitService.recordPurchaseCooldown(user, rewardItem.getPriceCoins());

        RewardRequest request = new RewardRequest();
        request.setUser(user);
        request.setRewardItem(rewardItem);
        request.setStatus(RewardRequestStatus.PENDING);
        request.setCreatedAt(LocalDateTime.now());
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
        userService.save(requester);
        if (isWithdrawal) {
            sinkShopService.reverseWithdrawal(requester, price);
        }
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
        userService.save(user);
        if (isWithdrawal) {
            sinkShopService.reverseWithdrawal(user, price);
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

    @Transactional
    public RewardRequest createUsdtWithdrawalRequest(AppUser user, long excAmount, long rubles, String tonWallet) {
        long remaining = sinkShopService.getRemainingWithdrawalLimit(user);
        if (excAmount > remaining) {
            throw new IllegalArgumentException("Превышен месячный лимит вывода. Доступно ещё: " + remaining + " EXC.");
        }
        if (excAmount > user.getCoins()) {
            throw new IllegalArgumentException("Недостаточно EXC. Ваш баланс: " + user.getCoins() + " EXC.");
        }

        RewardItem withdrawItem = new RewardItem();
        withdrawItem.setTitle("Вывод " + excAmount + " EXC → USDT (TON)");
        withdrawItem.setDescription("Заявка на вывод в USDT · Кошелёк: " + tonWallet);
        withdrawItem.setCategory("Вывод");
        withdrawItem.setPriceCoins(excAmount);
        withdrawItem.setActive(false);
        withdrawItem.setCreatedAt(LocalDateTime.now());
        RewardItem saved = rewardItemRepository.save(withdrawItem);

        sinkShopService.recordWithdrawal(user, excAmount);
        user.setCoins(user.getCoins() - excAmount);
        userService.save(user);

        RewardRequest request = new RewardRequest();
        request.setUser(user);
        request.setRewardItem(saved);
        request.setStatus(RewardRequestStatus.PENDING);
        request.setCreatedAt(LocalDateTime.now());
        request.setPayoutDetails("USDT·TON:" + tonWallet + ":rubles=" + rubles);
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

    @Transactional
    public RewardRequest createWithdrawalRequestWithDetails(AppUser user, long excAmount, long rubles, String payoutDetails) {
        RewardItem withdrawItem = new RewardItem();
        withdrawItem.setTitle("Вывод " + excAmount + " EXC → " + rubles + " ₽");
        withdrawItem.setDescription("Заявка на вывод средств");
        withdrawItem.setCategory("Вывод");
        withdrawItem.setPriceCoins(excAmount);
        withdrawItem.setActive(false);
        withdrawItem.setCreatedAt(LocalDateTime.now());
        RewardItem saved = rewardItemRepository.save(withdrawItem);

        sinkShopService.recordWithdrawal(user, excAmount);
        user.setCoins(user.getCoins() - excAmount);
        userService.save(user);

        RewardRequest request = new RewardRequest();
        request.setUser(user);
        request.setRewardItem(saved);
        request.setStatus(RewardRequestStatus.PENDING);
        request.setPayoutDetails(payoutDetails);
        request.setCreatedAt(LocalDateTime.now());
        return rewardRequestRepository.save(request);
    }

}
