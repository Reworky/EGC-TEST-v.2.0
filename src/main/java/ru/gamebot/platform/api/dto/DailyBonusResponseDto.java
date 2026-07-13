package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DailyBonusResponseDto {
    private boolean success;
    private String message;
    private long totalExc;
    private long dailyExc;
    private long milestoneExc;
    private long xpBonus;
    private int streakDays;
    private String milestoneText;
    private long newBalance;
}
