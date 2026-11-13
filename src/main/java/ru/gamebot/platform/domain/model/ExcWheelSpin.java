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

/** Одна выдача «Колеса за EXC» (13.11.2026): приз, заплаченная цена (0 у бесплатного повторного спина). Отдельно от wheel_spin_log: тот считает дневной лимит билетного колеса. */
@Entity
@Table(name = "exc_wheel_spins")
@Getter
@Setter
public class ExcWheelSpin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** TICKET, COUPON, INSURANCE, XP_BOOST, COOLDOWN, RESPIN или TICKET_FALLBACK (замена приза, который у игрока уже есть). */
    @Column(nullable = false, length = 24)
    private String prizeType;

    @Column(length = 100)
    private String prizeLabel;

    /** Сколько EXC списано за этот спин (0 - повторный спин бесплатный). */
    @Column(nullable = false)
    private long cost;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
