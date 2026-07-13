package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TournamentDto {
    private Long id;
    private String name;
    private String gameName;
    private long entryFeeExc;
    private long prizePoolExc;
    private long entryCount;
    private String startDate;
    private String endDate;
    private String status;
    private boolean entered;
}
