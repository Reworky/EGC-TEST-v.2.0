package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ShopActionResponseDto {
    private boolean success;
    private String message;
}
