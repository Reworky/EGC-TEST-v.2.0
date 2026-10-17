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

/** «Цель клуба» (2026-10-17). Два типа:
 *  REACTIONS - общая цель «набрать N реакций под постом дня»; счётчик приходит из Telegram (message_reaction_count), награду забирают
 *  те, кто ввёл код этого поста;
 *  LINK - портал в уже существующий (например, спонсорский) квест: заголовок, текст и кнопка, никаких своих бонусов и прогресса. */
@Getter
@Setter
@Entity
@Table(name = "club_goals")
public class ClubGoal {

    public static final String REACTIONS = "REACTIONS";
    public static final String LINK = "LINK";
    public static final String ACTIVE = "ACTIVE";
    public static final String DONE = "DONE";
    public static final String EXPIRED = "EXPIRED";
    public static final String CLOSED = "CLOSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "goal_type", nullable = false, length = 16)
    private String goalType;

    @Column(nullable = false, length = 16)
    private String status = ACTIVE;

    @Column(length = 160)
    private String title;

    @Column(length = 1000)
    private String description;

    @Column(columnDefinition = "integer default 0")
    private int targetCount;

    @Column(columnDefinition = "integer default 0")
    private int currentCount;

    /** Пост в канале, под которым считаются реакции (REACTIONS). */
    private Integer channelMessageId;

    /** Код дня этого поста: награду за цель получают те, кто его ввёл (REACTIONS). */
    private Long dailyCodeId;

    /** Квест, на который ведёт кнопка (LINK). */
    private Long questId;

    @Column(columnDefinition = "bigint default 0")
    private long rewardExc;

    @Column(columnDefinition = "integer default 0")
    private int rewardTickets;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime expiresAt;
    private LocalDateTime completedAt;
    /** Когда Telegram последний раз прислал счётчик реакций (для диагностики: если null - счётчик не приходит). */
    private LocalDateTime countUpdatedAt;
}
