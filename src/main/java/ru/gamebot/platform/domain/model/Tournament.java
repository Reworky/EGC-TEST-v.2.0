package ru.gamebot.platform.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "tournaments")
public class Tournament {

    public enum Status { REGISTRATION, ACTIVE, FINISHED }
    public enum ScoringType { QUEST_COUNT, BRAWL_TROPHIES }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String gameName;

    @Column(nullable = false)
    private long entryFeeExc;

    @Column(columnDefinition = "bigint default 0")
    private long prizePoolExc;

    private LocalDateTime startDate;
    private LocalDateTime endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(32) default 'QUEST_COUNT'")
    private ScoringType scoringType = ScoringType.QUEST_COUNT;

    private LocalDateTime createdAt;
}
