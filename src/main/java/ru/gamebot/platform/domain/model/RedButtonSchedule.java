package ru.gamebot.platform.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Запланированная «красная кнопка» (21.10.2026): день и случайное время старта, процент бонуса, статус жизненного цикла. Время - UTC. */
@Getter
@Setter
@Entity
@Table(name = "red_button_schedule")
public class RedButtonSchedule {

    public static final String PLANNED = "PLANNED";
    public static final String PUBLISHING = "PUBLISHING";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String CLOSED = "CLOSED";
    public static final String CANCELLED = "CANCELLED";
    public static final String MISSED = "MISSED";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Понедельник недели, для которой запланирована кнопка. */
    @Column(nullable = false)
    private LocalDate weekStart;

    @Column(nullable = false)
    private LocalDateTime plannedAt;

    @Column(nullable = false)
    private int percent;

    @Column(nullable = false, length = 16)
    private String status = PLANNED;

    private LocalDateTime statusAt;

    /** Запись daily_bonus этой кнопки (создаётся перед публикацией поста). */
    private Long bonusId;

    private Long channelMessageId;

    /** Текст опубликованного поста: нужен, чтобы после закрытия окна отредактировать пост, добавив итог. */
    @Column(length = 3000)
    private String postText;

    /** Первая кнопка недели: перед ней выходит тизер. */
    @Column(nullable = false)
    private boolean firstOfWeek;

    @Column(nullable = false)
    private boolean teaserSent;

    @Column(nullable = false)
    private boolean warned;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
