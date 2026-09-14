package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

/** Буст выходных на EXC за квесты — см. QuestController.boost() / QuestRewardBoostService. Параллель
 *  с ReferralDto.boostActive/boostMultiplier/boostEndsAt (тот же формат полей для фронтенда). */
@Data
@Builder
public class QuestBoostDto {
    private boolean active;
    private Integer multiplier;
    private String endsAt;
}
