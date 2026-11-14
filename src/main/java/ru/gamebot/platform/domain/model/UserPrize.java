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

/** Предмет в запасе игрока (14.11.2026): приз колеса или слота не включается сразу, а ждёт в «Мои призы» - игрок сам выбирает, что и когда применить. */
@Entity
@Table(name = "user_prizes")
@Getter
@Setter
public class UserPrize {

    public static final String AVAILABLE = "AVAILABLE";
    public static final String USED = "USED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** COUPON, INSURANCE, XP_BOOST, COOLDOWN, EXTRA_SLOT, PASS. */
    @Column(nullable = false, length = 24)
    private String itemType;

    /** WHEEL или SLOT. */
    @Column(length = 8)
    private String source;

    @Column(nullable = false, length = 12)
    private String status = AVAILABLE;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime usedAt;
}
