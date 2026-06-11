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

    public List<RewardItem> findAvailableRewards() {
        return rewardItemRepository.findAllByActiveTrueOrderByPriceCoinsAsc();
    }

    public RewardItem getRewardItem(Long rewardId) {
        return rewardItemRepository.findById(rewardId)
                .orElseThrow(() -> new IllegalArgumentException("Награда не найдена."));
    }

    @Transactional
    public RewardRequest createRewardRequest(AppUser user, RewardItem rewardItem) {
        if (user.getCoins() < rewardItem.getPriceCoins()) {
            throw new IllegalArgumentException("Недостаточно монет для обмена.");
        }
        user.setCoins(user.getCoins() - rewardItem.getPriceCoins());
        userService.save(user);

        RewardRequest request = new RewardRequest();
        request.setUser(user);
        request.setRewardItem(rewardItem);
        request.setStatus(RewardRequestStatus.PENDING);
        request.setCreatedAt(LocalDateTime.now());
        return rewardRequestRepository.save(request);
    }

    @Transactional
    public RewardItem createRewardItem(String title, String description, String category, long priceCoins) {
        RewardItem item = new RewardItem();
        item.setTitle(title);
        item.setDescription(description);
        item.setCategory(category);
        item.setPriceCoins(priceCoins);
        item.setActive(true);
        item.setCreatedAt(LocalDateTime.now());
        return rewardItemRepository.save(item);
    }
}
