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
