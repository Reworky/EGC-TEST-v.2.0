package ru.gamebot.platform.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** Суточный снимок «Пульса проекта»: одна строка на метрику (и на итог сигнала, ключ «signal:N», и на итог проекта, ключ «project»).
 *  rawStatus - статус по порогам без правила «два отчёта подряд», shownStatus - то, что видел владелец (красный только со второго нарушения). */
@Getter
@Setter
@Entity
@Table(name = "pulse_snapshot_entries", indexes = {
        @Index(name = "idx_pulse_date", columnList = "snapshotDate"),
        @Index(name = "idx_pulse_key_date", columnList = "metricKey,snapshotDate")})
public class PulseSnapshotEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate snapshotDate;

    @Column(nullable = false, length = 40)
    private String metricKey;

    @Column(nullable = false)
    private int signalNo;

    /** null - метрику не удалось посчитать (серый статус). */
    private Double metricValue;

    @Column(nullable = false, length = 8)
    private String rawStatus;

    @Column(nullable = false, length = 8)
    private String shownStatus;
}
