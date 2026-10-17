package ru.gamebot.platform.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Игрок забрал награду за «Цель недели» (WeeklyGoalService). Уникальность (игрок, понедельник недели) защищает от двойной выдачи. */
@Getter
@Setter
@Entity
@Table(name = "weekly_goal_claims",
        uniqueConstraints = @UniqueConstraint(name = "uq_wgc_user_week", columnNames = {"user_id", "week_start"}))
public class WeeklyGoalClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Понедельник недели, за которую выдана награда. */
    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    @Column(nullable = false)
    private LocalDateTime claimedAt;
}
