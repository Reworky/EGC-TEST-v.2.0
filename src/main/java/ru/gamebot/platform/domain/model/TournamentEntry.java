package ru.gamebot.platform.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "tournament_entries")
public class TournamentEntry {

    public enum SnapshotStatus { PENDING, OK, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tournament_id", nullable = false)
    private Tournament tournament;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(nullable = false)
    private long entryFeeExc;

    @Column(columnDefinition = "integer default 0")
    private int rank;

    @Column(columnDefinition = "bigint default 0")
    private long prizeExc;

    private LocalDateTime createdAt;

    // Brawl Stars trophy-marathon fields (null/default for QUEST_COUNT tournaments)
    private String gameTag;
    private Integer trophiesAtRegistration;
    private Integer trophiesStart;
    private Integer trophiesEnd;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(16) default 'PENDING'")
    private SnapshotStatus snapshotStatus = SnapshotStatus.PENDING;

    @Column(columnDefinition = "boolean default false")
    private boolean anomalyFlag;

    @Column(columnDefinition = "boolean default false")
    private boolean anomalyResolved;

    @Column(columnDefinition = "boolean default false")
    private boolean disqualified;

    @Column(columnDefinition = "boolean default false")
    private boolean payoutHeld;
}
