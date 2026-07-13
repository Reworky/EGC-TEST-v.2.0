package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WithdrawalRequestDto {
    private Long id;
    private long displayId;
    private long amountExc;
    private String status;
    private String adminComment;
    private String createdAt;
    /** "TON" или "Рубли". */
    private String method;
    /** Адрес TON-кошелька или банковские реквизиты — в зависимости от method. */
    private String details;
}
