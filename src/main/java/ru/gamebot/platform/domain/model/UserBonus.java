package ru.gamebot.platform.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Игрок активировал бонус к курсу. Уникальность (игрок, бонус) закрывает гонку двойного нажатия: второй insert падает. */
@Getter
@Setter
@Entity
@Table(name = "user_bonus",
        uniqueConstraints = @UniqueConstraint(name = "uq_ub_user_bonus", columnNames = {"user_id", "bonus_id"}))
public class UserBonus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "bonus_id", nullable = false)
    private Long bonusId;

    @Column(nullable = false)
    private int percent;

    @Column(nullable = false)
    private LocalDateTime activatedAt;

    @Column(nullable = false)
    private LocalDateTime validUntil;
}
