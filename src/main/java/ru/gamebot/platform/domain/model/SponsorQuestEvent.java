package ru.gamebot.platform.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Событие воронки спонсорского квеста «подпишись на канал»: кому и когда мы его показали / отправили, кто открыл карточку.
 *  «Взяли / выполнили / остались» берутся из заявок на квест, здесь только то, чего в заявках нет. kind - см. SponsorQuestPromoService.KIND_*. */
@Getter
@Setter
@Entity
@Table(name = "sponsor_quest_events", indexes = @Index(name = "idx_sqe_quest_kind", columnList = "quest_id, kind"))
public class SponsorQuestEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "quest_id", nullable = false)
    private Long questId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 16)
    private String kind;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
