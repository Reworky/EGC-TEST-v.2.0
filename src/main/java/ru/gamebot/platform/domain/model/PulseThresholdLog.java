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

/** Журнал изменений порогов «Пульса проекта»: кто и когда поменял границы зелёного и красного. */
@Getter
@Setter
@Entity
@Table(name = "pulse_threshold_log")
public class PulseThresholdLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String metricKey;

    @Column(length = 40)
    private String oldValues;

    @Column(length = 40)
    private String newValues;

    private Long changedByTelegramId;

    @Column(nullable = false)
    private LocalDateTime changedAt = LocalDateTime.now();
}
