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

/** Воронка партнёрского квеста банка (10.11.2026): показ карточки квеста (CARD_VIEWED) и нажатие «Начать выполнять квест» (START_CLICKED). Взятия, проверка и выплата банка берутся из заявок на квест. */
@Getter
@Setter
@Entity
@Table(name = "partner_quest_events")
public class PartnerQuestEvent {

    public static final String CARD_VIEWED = "CARD_VIEWED";
    public static final String START_CLICKED = "START_CLICKED";
    /** Открытие карточки любого спонсорского квеста (19.11.2026, вкладка «Спонсорские квесты»): с полем questId. Отдельный тип, чтобы не смешивать с CARD_VIEWED партнёрского квеста ОТП. */
    public static final String SPONSOR_VIEWED = "SPONSOR_VIEWED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "telegram_id", nullable = false)
    private Long telegramId;

    @Column(nullable = false, length = 24)
    private String type;

    /** Квест, чья карточка открыта (только у SPONSOR_VIEWED; у прежних событий пусто). */
    @Column(name = "quest_id")
    private Long questId;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
