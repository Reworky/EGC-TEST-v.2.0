package ru.gamebot.platform.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Разовая награда игроку за задание «Соцсети» (подписка на канал): одна запись на пару (игрок, задание) - по ней же защита от повторной выплаты. */
@Getter
@Setter
@Entity
@Table(name = "social_task_claims", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "task_code"}))
public class SocialTaskClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "task_code", nullable = false, length = 40)
    private String taskCode;

    @Column(nullable = false)
    private long rewardExc;

    private LocalDateTime createdAt;
}
