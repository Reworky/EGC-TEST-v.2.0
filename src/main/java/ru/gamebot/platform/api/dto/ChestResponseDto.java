package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

/** Результат открытия "Сундука дня" — см. WalletController.openChest() / UserService.openChest(). */
@Data
@Builder
public class ChestResponseDto {
    private boolean success;
    private String message;
    private String prizeLabel;
    private long exc;
    private int tickets;
    private long newBalance;
}
