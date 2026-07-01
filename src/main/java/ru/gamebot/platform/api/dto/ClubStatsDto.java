package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ClubStatsDto {
    private long totalPlayers;
    private long totalQuestsCompleted;
    private long totalExcIssued;
    private String topGame;
    private int healthRatioPercent;
    private long payoutPoolRub;
}
