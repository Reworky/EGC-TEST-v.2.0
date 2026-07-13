package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TournamentEntryDto {
    private int rank;
    private String nickname;
    private long prizeExc;
}
