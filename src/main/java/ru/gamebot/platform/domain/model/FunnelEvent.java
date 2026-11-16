package ru.gamebot.platform.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Воронка новичка (17.11.2026): первое достижение игроком шага воронки (показан слайд, экран выбора пути, раздел спонсорских квестов, карточка банка и т.д.).
 *  Одна строка на пару «игрок + шаг» (повторы не пишутся), поэтому счёт идёт по людям, а не по нажатиям. Шаги и их порядок - в NewbieFunnelService. */
@Getter
@Setter
@Entity
@Table(name = "funnel_events",
        uniqueConstraints = @UniqueConstraint(name = "uq_funnel_user_step", columnNames = {"telegram_id", "step"}),
        indexes = @Index(name = "idx_funnel_step", columnList = "step"))
public class FunnelEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "telegram_id", nullable = false)
    private Long telegramId;

    @Column(nullable = false, length = 32)
    private String step;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
