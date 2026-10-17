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

/** Игрок ввёл код дня. Уникальность (игрок, календарный день) закрывает гонку двойного нажатия: второй insert падает. */
@Getter
@Setter
@Entity
@Table(name = "daily_code_claims",
        uniqueConstraints = @UniqueConstraint(name = "uq_dcc_user_date", columnNames = {"user_id", "claim_date"}))
public class DailyCodeClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "code_id", nullable = false)
    private Long codeId;

    @Column(name = "claim_date", nullable = false)
    private LocalDate claimDate;

    @Column(nullable = false)
    private LocalDateTime claimedAt;
}
