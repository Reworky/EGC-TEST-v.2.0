package ru.gamebot.platform.domain.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "wheel_spin_log")
@Getter
@Setter
public class WheelSpinLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(nullable = false)
    private int ticketsSpent = 1;

    /** EXC, BOOST_24H, AVATAR_FRAME */
    @Column(nullable = false, length = 32)
    private String rewardType;

    /** EXC amount, or null for non-EXC rewards */
    private Long rewardAmount;

    /** Human-readable label of the prize */
    @Column(length = 100)
    private String rewardLabel;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
