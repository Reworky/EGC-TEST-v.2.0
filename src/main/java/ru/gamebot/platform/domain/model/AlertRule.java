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

/** Правило алерта: «уведомить, если [метрика] изменилась более чем на X% за Y дней» (по ежедневным снимкам платформы). */
@Getter
@Setter
@Entity
@Table(name = "alert_rules")
public class AlertRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String metricKey;

    /** Порог изменения в процентах (по модулю). */
    private int thresholdPercent;

    /** Окно сравнения в днях: сегодняшний снимок против снимка N дней назад. */
    private int days;

    private boolean enabled = true;

    /** Когда правило сработало в последний раз - повторно не шлём, пока не прошло окно. */
    private LocalDateTime lastFiredAt;

    private LocalDateTime createdAt = LocalDateTime.now();
}
