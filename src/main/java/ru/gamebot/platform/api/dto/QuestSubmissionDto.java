package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class QuestSubmissionDto {
    private Long id;
    private String questTitle;
    private String gameName;
    private String category;
    private String status;
    private long rewardXp;
    private long rewardCoins;
    private String moderatorComment;
    private String createdAt;
    private String updatedAt;
}
