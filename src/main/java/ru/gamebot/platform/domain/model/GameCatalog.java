package ru.gamebot.platform.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "game_catalog")
public class GameCatalog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "game_name", nullable = false, unique = true)
    private String gameName;

    @Column(name = "photo_file_id")
    private String photoFileId;

    @Column(name = "difficulty_mode", columnDefinition = "VARCHAR(10) DEFAULT 'TIERED'")
    private String difficultyMode = "TIERED";

    @Column(name = "flat_reward_exc", columnDefinition = "BIGINT DEFAULT 1500")
    private long flatRewardExc = 1500;

    @Column(name = "flat_reward_xp", columnDefinition = "INT DEFAULT 50")
    private int flatRewardXp = 50;
}
