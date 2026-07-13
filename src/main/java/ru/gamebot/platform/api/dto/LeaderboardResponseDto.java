package ru.gamebot.platform.api.dto;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LeaderboardResponseDto {
    private List<LeaderboardEntryDto> entries;
    /** Место и XP запросившего пользователя (даже если он не попал в топ-20). null для анонимных запросов. */
    private LeaderboardEntryDto me;
}
