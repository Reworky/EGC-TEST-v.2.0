package ru.gamebot.platform.api.dto;

import lombok.Data;

@Data
public class PurchaseRequestDto {
    /** Данные игрока, если у товара задан userDataPrompt (ID аккаунта и т.п.). */
    private String userData;
}
