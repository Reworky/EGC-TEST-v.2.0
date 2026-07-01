package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RewardRequestDto {
    private Long id;
    private String rewardTitle;
    private String category;
    private long priceCoins;
    private String status;
    private String adminComment;
    private String createdAt;
}
