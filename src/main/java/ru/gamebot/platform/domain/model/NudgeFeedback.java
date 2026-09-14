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

/** Причина, по которой игрок не вернулся за вторым квестом — собирается кнопками прямо в
 *  напоминании про второй квест (см. GamePlatformBot "nudgefb:" callback), без отдельной рассылки
 *  или полноценного опроса (аудит вовлечённости, 2026-09-14). */
@Getter
@Setter
@Entity
@Table(name = "nudge_feedback")
public class NudgeFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long telegramId;

    private String nickname;

    @Column(nullable = false, length = 32)
    private String reasonCode;

    @Column(nullable = false, length = 255)
    private String reasonLabel;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
