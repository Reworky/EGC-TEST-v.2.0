package ru.gamebot.platform.domain.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "traffic_sources")
@Getter
@Setter
public class TrafficSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(columnDefinition = "bigint default 0")
    private long clicks;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
