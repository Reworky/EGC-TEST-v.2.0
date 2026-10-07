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

/** «Хочу» на ещё не запущенную функцию (проверка спроса без разработки): один игрок - одна запись на функцию (проверка existsBy... в коде, без уникального индекса).
 *  Сейчас используется для платного «Ускорителя» (код ACCELERATOR, 2026-10-07). */
@Getter
@Setter
@Entity
@Table(name = "feature_interests")
public class FeatureInterest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long telegramId;

    @Column(nullable = false, length = 32)
    private String featureCode;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
