package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RewardItemDto {
    private Long id;
    private String title;
    private String description;
    private String category;
    private long priceCoins;
    private long effectivePrice;
}
