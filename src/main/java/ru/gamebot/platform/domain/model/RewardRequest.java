package ru.gamebot.platform.domain.model;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import ru.gamebot.platform.domain.enums.RewardRequestStatus;

@Getter
@Setter
@Entity
@Table(name = "reward_requests")
public class RewardRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reward_id")
    private RewardItem rewardItem;

    @Enumerated(EnumType.STRING)
    private RewardRequestStatus status;

    private LocalDateTime createdAt;
    private String adminComment;

    /** Детали выплаты (для USDT: адрес кошелька TON) */
    private String payoutDetails;
}
