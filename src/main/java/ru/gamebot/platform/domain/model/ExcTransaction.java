package ru.gamebot.platform.domain.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "exc_transactions")
public class ExcTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 32)
    private String type;

    @Column(length = 255)
    private String description;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** Баланс пользователя сразу после операции. Null для записей, созданных до введения этого поля. */
    private Long balanceAfter;
}
