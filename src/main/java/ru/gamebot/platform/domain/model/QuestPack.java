package ru.gamebot.platform.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * Пачка квестов одной игры. В каждой игре с пачками включена ровно одна — её квесты видны игрокам,
 * квесты остальных пачек скрыты (см. QuestPackService). seederManaged = «Основная» пачка: её состав и
 * активность по-прежнему ведёт QuestSeeder, остальные пачки сидер не трогает.
 */
@Getter
@Setter
@Entity
@Table(name = "quest_packs")
public class QuestPack {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String gameName;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "boolean default false")
    private boolean active;

    @Column(columnDefinition = "boolean default false")
    private boolean seederManaged;

    private LocalDateTime createdAt;
}
