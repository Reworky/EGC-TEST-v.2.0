package ru.gamebot.platform.domain.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "exc_transfers",
       indexes = {
           @Index(name = "idx_transfer_sender", columnList = "sender_id"),
           @Index(name = "idx_transfer_created", columnList = "created_at")
       })
public class ExcTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id", nullable = false)
    private AppUser sender;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiver_id", nullable = false)
    private AppUser receiver;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false)
    private long commission;

    @Column(nullable = false)
    private long totalDebited;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
