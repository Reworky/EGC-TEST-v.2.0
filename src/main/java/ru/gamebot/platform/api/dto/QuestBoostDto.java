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
    /** Процент буста и на сколько первых квестов игрока он действует (мини-апп показывает «+50% за первые 3 квеста»);
     * multiplier оставлен для старых версий мини-аппа. */
    private Integer percent;
    private Integer maxQuests;
    private String endsAt;
}
