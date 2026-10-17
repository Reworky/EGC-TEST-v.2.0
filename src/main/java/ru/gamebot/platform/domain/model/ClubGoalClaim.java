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

/** Игрок забрал награду за выполненную цель клуба; уникальность (цель, игрок) защищает от двойной выдачи. */
@Getter
@Setter
@Entity
@Table(name = "club_goal_claims",
        uniqueConstraints = @UniqueConstraint(name = "uq_cgc_goal_user", columnNames = {"goal_id", "user_id"}))
public class ClubGoalClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "goal_id", nullable = false)
    private Long goalId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDateTime claimedAt;
}
