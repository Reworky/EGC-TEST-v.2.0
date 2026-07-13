package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TonQuoteDto {
    private long rubles;
    private String tonAmount;
    private String tonRate;
    private boolean usingFallback;
}
