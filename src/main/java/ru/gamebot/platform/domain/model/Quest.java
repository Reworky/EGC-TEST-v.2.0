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

@Getter
@Setter
@Entity
@Table(name = "quests")
public class Quest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, length = 4000)
    private String description;

    private String gameName;
    private String category;
    private String platform;
    private String durationText;
    private int durationDays;
    private Integer participantLimit;

    @Column(length = 2000)
    private String requirements;

    @Column(length = 4000)
    private String instruction;

    private long rewardXp;
    private long rewardCoins;
    private boolean active;
    private boolean councilOnly;
    private String photoFileId;
    private LocalDateTime createdAt;
}
