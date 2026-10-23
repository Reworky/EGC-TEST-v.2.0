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

/** Воронка продаж EGC Pass (23.10.2026): показ предложения (OFFER_SENT, source - откуда) и открытие экрана Pass (SCREEN_OPENED). Покупки берутся из stars_purchases. */
@Getter
@Setter
@Entity
@Table(name = "egc_pass_events")
public class EgcPassEvent {

    public static final String OFFER_SENT = "OFFER_SENT";
    public static final String SCREEN_OPENED = "SCREEN_OPENED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "telegram_id", nullable = false)
    private Long telegramId;

    @Column(nullable = false, length = 24)
    private String type;

    @Column(length = 32)
    private String source;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
