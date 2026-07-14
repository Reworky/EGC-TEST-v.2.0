package ru.gamebot.platform.api.dto;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PollDto {
    private Long id;
    private String question;
    private List<String> options;
    private long[] voteCounts;
    private long totalVotes;
    private long priceExc;
    private String closesAt;
    private boolean closed;
    private boolean voted;
}
