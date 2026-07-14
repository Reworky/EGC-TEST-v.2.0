package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SupportTicketDto {
    private Long id;
    private String status;
    private String initialMessage;
    private String lastModeratorReply;
    private String createdAt;
    private String updatedAt;
}
