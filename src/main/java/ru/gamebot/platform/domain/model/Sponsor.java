package ru.gamebot.platform.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "sponsors")
public class Sponsor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String campaignName;

    /** Total EXC budget allocated for this campaign */
    @Column(nullable = false)
    private long budgetExc;

    /** How much EXC has already been issued to players */
    @Column(columnDefinition = "bigint default 0")
    private long spentExc;

    /** Original payment in RUB from sponsor (for reporting) */
    @Column(columnDefinition = "bigint default 0")
    private long paidRub;

    private LocalDateTime startDate;
    private LocalDateTime endDate;

    @Column(columnDefinition = "boolean default true")
    private boolean active;

    private LocalDateTime createdAt;
}
