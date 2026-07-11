package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class QuestActionResponseDto {
    private boolean success;
    private String status;
    private long minutesLeft;
    private String message;
}
