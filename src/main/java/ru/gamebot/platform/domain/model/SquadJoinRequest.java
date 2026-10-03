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

/** Заявка игрока на вступление в отряд с закрытым набором: капитан принимает или отклоняет. */
@Getter
@Setter
@Entity
@Table(name = "squad_join_requests")
public class SquadJoinRequest {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String DECLINED = "DECLINED";
    public static final String CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long squadId;

    /** AppUser.id заявителя (не telegramId). */
    @Column(nullable = false)
    private Long userId;

    @Column(length = 20)
    private String status = PENDING;

    private LocalDateTime createdAt = LocalDateTime.now();
}
