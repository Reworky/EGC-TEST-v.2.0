package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LeaderboardEntryDto {
    private int rank;
    private Long telegramId;
    private String nickname;
    private String levelName;
    private String profileTitle;
    private long xp;
    private int completedQuests;
}
