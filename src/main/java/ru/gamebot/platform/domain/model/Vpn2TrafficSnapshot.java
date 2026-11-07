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

/** Снимок суммарного трафика inbound раз в час (панель отдаёт только накопленные числа): разница двух снимков даёт трафик за сутки. Хранится 14 дней. */
@Getter
@Setter
@Entity
@Table(name = "vpn2_traffic_snapshots")
public class Vpn2TrafficSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime takenAt;

    @Column(nullable = false)
    private long downBytes;

    @Column(nullable = false)
    private long upBytes;
}
