package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RewardItemDto {
    private Long id;
    private String title;
    private String description;
    private String category;
    private long priceCoins;
    private long effectivePrice;
    /** Человекочитаемый статус доступности (уровень/cooldown/лимиты) — уже готовая строка для отображения. */
    private String statusNote;
    /** true, если товар сейчас нельзя купить (статус начинается с 🔒/⏳/🚫). */
    private boolean locked;
    /** Если задано — перед покупкой нужно запросить у игрока эти данные (см. userDataPrompt). */
    private String userDataPrompt;
}
