package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

/** Итог «Забрать сундук заданий»: status = OK | NOT_READY | ALREADY_CLAIMED | CAP_REACHED | DISABLED. */
@Data
@Builder
public class DailyTasksClaimDto {
    private boolean success;
    private String status;
    private long exc;
    private int tickets;
    private String message;
}
