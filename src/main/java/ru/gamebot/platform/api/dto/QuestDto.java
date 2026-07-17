package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class QuestDto {
    private Long id;
    private String title;
    private String description;
    private String gameName;
    private String category;
    private String platform;
    private int durationDays;
    private long rewardXp;
    private long rewardCoins;
    private boolean councilOnly;
    private boolean sponsored;

    /** null, если пользователь ещё не брал этот квест */
    private String submissionStatus;
}
