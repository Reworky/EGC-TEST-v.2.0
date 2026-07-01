package ru.gamebot.platform.api.controller;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.gamebot.platform.api.dto.RewardItemDto;
import ru.gamebot.platform.service.HealthRatioService;
import ru.gamebot.platform.service.RewardService;

@RestController
@RequestMapping("/api/shop")
@RequiredArgsConstructor
public class ShopController {

    private final RewardService rewardService;
    private final HealthRatioService healthRatioService;

    @GetMapping("/items")
    public List<RewardItemDto> items() {
        return rewardService.findAvailableRewards().stream().map(item -> RewardItemDto.builder()
                .id(item.getId())
                .title(item.getTitle())
                .description(item.getDescription())
                .category(item.getCategory())
                .priceCoins(item.getPriceCoins())
                .effectivePrice(rewardService.effectivePrice(item))
                .build()).toList();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        int ratioPercent = (int) Math.round(healthRatioService.getCurrentRatio() * 100);
        return Map.of(
                "healthRatioPercent", ratioPercent,
                "payoutPoolRub", healthRatioService.getPayoutPoolRub(),
                "totalDebtExc", healthRatioService.getTotalDebtExc(),
                "baseRate", "100 EXC = 1 ₽"
        );
    }
}
