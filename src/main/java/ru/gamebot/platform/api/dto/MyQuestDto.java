package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MyQuestDto {
    private Long submissionId;
    private Long questId;
    private String title;
    private String gameName;
    private String category;
    private String status;
    private String updatedAt;
    private String expiresAt;
    private String moderatorComment;
    private long rewardXp;
    private long rewardCoins;
}
