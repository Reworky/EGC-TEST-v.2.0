package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserProfileDto {
    private Long telegramId;
    private String nickname;
    private String country;
    private String platformsCsv;
    private String interestsCsv;
    private String profileTitle;
    private long xp;
    private long coins;
    private int level;
    private String levelName;
    private int completedQuests;
    private int streakDays;
    private long monthlyWithdrawalLimit;
    private long remainingWithdrawalLimit;
    private boolean hasAvatar;
}
