package ru.gamebot.platform.service;

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

    public List<RewardItem> findAvailableRewards() {
        return rewardItemRepository.findAllByActiveTrueOrderByPriceCoinsAsc();
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

        RewardRequest request = new RewardRequest();
        request.setUser(user);
        request.setRewardItem(rewardItem);
        request.setStatus(RewardRequestStatus.PENDING);
        request.setCreatedAt(LocalDateTime.now());
        return rewardRequestRepository.save(request);
    }

    public List<RewardRequest> findPendingRequests() {
        return rewardRequestRepository.findAllByStatusOrderByCreatedAtAsc(RewardRequestStatus.PENDING);
    }

    public List<RewardRequest> findUserRequests(AppUser user) {
        return rewardRequestRepository.findAllByUserOrderByCreatedAtDesc(user);
    }

    public long countPendingRequests() {
        return rewardRequestRepository.countByStatus(RewardRequestStatus.PENDING);
    }

    public RewardRequest getRequest(Long requestId) {
        return rewardRequestRepository.findWithUserAndRewardItemById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена."));
    }

    @Transactional
    public RewardRequest approveRequest(Long requestId) {
        RewardRequest req = getRequest(requestId);
        req.setStatus(RewardRequestStatus.APPROVED);
        return rewardRequestRepository.save(req);
    }

    @Transactional
    public RewardRequest rejectRequest(Long requestId, String comment) {
        RewardRequest req = getRequest(requestId);
        req.setStatus(RewardRequestStatus.REJECTED);
        req.setAdminComment(comment);
        // Refund EXC
        AppUser user = req.getUser();
        long price = effectivePrice(req.getRewardItem());
        user.setCoins(user.getCoins() + price);
        userService.save(user);
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
}
