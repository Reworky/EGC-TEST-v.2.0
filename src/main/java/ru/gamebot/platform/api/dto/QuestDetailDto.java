package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class QuestDetailDto {
    private Long id;
    private String title;
    private String description;
    private String instruction;
    private String requirements;
    private String gameName;
    private String category;
    private String platform;
    private int durationDays;
    private long rewardXp;
    private long rewardCoins;
    private int ticketReward;
    private boolean councilOnly;

    /** null, если пользователь ещё не брал этот квест */
    private String submissionStatus;
    private String moderatorComment;
    private String expiresAt;
}
