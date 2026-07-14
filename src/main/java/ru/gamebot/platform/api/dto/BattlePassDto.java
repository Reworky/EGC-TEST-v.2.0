package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BattlePassDto {
    private boolean hasSeason;
    private Long seasonId;
    private String name;
    private long priceExc;
    private int xpBoostPercent;
    private String startDate;
    private String endDate;
    private boolean hasActivePass;
    private String passActiveUntil;
    private long userCoins;
}
