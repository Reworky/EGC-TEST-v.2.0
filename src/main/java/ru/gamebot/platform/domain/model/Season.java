package ru.gamebot.platform.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "seasons")
public class Season {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private long priceExc;

    private LocalDateTime startDate;
    private LocalDateTime endDate;

    @Column(columnDefinition = "integer default 10")
    private int xpBoostPercent;

    @Column(columnDefinition = "boolean default true")
    private boolean active;

    private LocalDateTime createdAt;
}
