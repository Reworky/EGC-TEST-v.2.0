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

/** Отзыв или причина отказа в боте EGC VPN: оценка (5 - отлично ... 2 - плохо), причина кнопкой, текст и согласие на публикацию.
 *  Публикуется в канал отзывов только после согласия человека и одобрения админом. */
@Getter
@Setter
@Entity
@Table(name = "vpn2_feedback")
public class Vpn2Feedback {

    public static final String KIND_TRIAL = "TRIAL";
    public static final String KIND_WEEK = "WEEK";
    public static final String KIND_CHURN = "CHURN";

    /** NEW - только оценка/причина; PENDING - человек согласился на публикацию, ждёт модерации; PUBLISHED - в канале; REJECTED - админ отклонил. */
    public static final String STATUS_NEW = "NEW";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long telegramId;

    @Column(nullable = false, length = 16)
    private String kind;

    /** 5 - отлично, 4 - нормально, 3 - так себе, 2 - плохо; у опроса отказа (CHURN) оценки нет. */
    private Integer rating;

    /** Причина кнопкой: speed, conn, app, price, other (оценка) или price, slow, broken, notneeded, other (отказ). */
    @Column(length = 32)
    private String reason;

    @Column(length = 1000)
    private String reviewText;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean publishConsent = false;

    @Column(nullable = false, length = 16, columnDefinition = "varchar(16) default 'NEW'")
    private String status = STATUS_NEW;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime publishedAt;
}
