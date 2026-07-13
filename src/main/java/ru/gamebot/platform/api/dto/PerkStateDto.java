package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PerkStateDto {
    private long coins;
    private String profileTitle;
    private boolean xpBoostActive;
    private String xpBoostUntil;
    private boolean excBoostActive;
    private String excBoostUntil;
    private boolean insuranceActive;
    private boolean extraSlotActive;
    private String extraSlotUntil;
    private boolean cooldownBypassActive;
}
