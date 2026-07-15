package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WalletDto {
    private long coins;
    private int excBonusPercent;
    private long tickets;
    private long xp;
    private long weeklyXp;
    private int healthRatioPercent;
    private long monthlyWithdrawalLimit;
    private long remainingWithdrawalLimit;
    private boolean dailyBonusAvailable;
    private int streakDays;
    private long nextDailyBonusExc;
    private long fixedRubBalance;
}
