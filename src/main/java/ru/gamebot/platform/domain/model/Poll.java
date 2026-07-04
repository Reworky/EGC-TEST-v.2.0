package ru.gamebot.platform.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "polls")
public class Poll {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String question;

    @Column(nullable = false, length = 2000)
    private String optionsCsv;

    @Column(nullable = false)
    private long priceExc;

    private LocalDateTime closesAt;

    @Column(columnDefinition = "boolean default false")
    private boolean closed;

    private LocalDateTime createdAt;
}
