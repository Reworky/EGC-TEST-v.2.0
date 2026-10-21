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

/** Бонус к курсу вывода на один день («Курс дня», 21.10.2026). Процент и бюджет фиксируются при создании поста; окно активации открывается при публикации. */
@Getter
@Setter
@Entity
@Table(name = "daily_bonus")
public class DailyBonus {

    public static final String DAILY = "DAILY";
    public static final String RED = "RED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bonus_date", nullable = false)
    private LocalDate bonusDate;

    @Column(nullable = false, length = 16)
    private String type = DAILY;

    @Column(nullable = false)
    private int percent;

    /** Бюджет бонусов этого дня в рублях и сколько уже зарезервировано заявками на вывод (возвращается при отмене/отклонении). */
    @Column(nullable = false)
    private long budgetTotalRub;

    @Column(nullable = false)
    private long budgetUsedRub;

    /** Когда пост опубликован в канале (с этого момента можно активировать) и до какого момента можно активировать. null - пост ещё не вышел.
     *  У DAILY бонус игрока действует до endsAt, у RED окно активации - до endsAt, а бонус игрока живёт дольше (user_bonus.validUntil = активация + несколько часов). */
    private LocalDateTime publishedAt;

    private LocalDateTime endsAt;

    private Long channelMessageId;

    /** Сколько раз игроки открыли бота по кнопке под постом (с повторами, до проверки подписки): для конверсии нажатие -> активация. */
    @Column(columnDefinition = "integer default 0")
    private int clicks;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
