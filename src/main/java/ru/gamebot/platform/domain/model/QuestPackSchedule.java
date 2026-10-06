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

/** Расписание автоматической смены пачек квестов одной игры. Время — по Москве (см. QuestPackScheduleService). */
@Getter
@Setter
@Entity
@Table(name = "quest_pack_schedules")
public class QuestPackSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String gameName;

    @Column(columnDefinition = "boolean default false")
    private boolean enabled;

    /** Как часто менять пачку, дней. */
    @Column(columnDefinition = "integer default 14")
    private int cycleDays = 14;

    /** День недели смены: 1 = понедельник ... 7 = воскресенье. */
    @Column(columnDefinition = "integer default 1")
    private int dayOfWeek = 1;

    /** Час смены по Москве. Колонка не «hour»: это зарезервированное слово H2 — запросы к таблице падали с синтаксической ошибкой. */
    @Column(name = "switch_hour", columnDefinition = "integer default 12")
    private int hour = 20; // 20:00 МСК = 00:00 у владельца (МСК+4): слот для постов о смене пачек в сетке контент-плана

    /** Когда ротацию включили последний раз — точка отсчёта «до/после» для аналитики (вкладка «Пачки»). */
    private LocalDateTime enabledSince;

    private LocalDateTime nextSwitchAt;
    private LocalDateTime lastSwitchAt;

    /** Напоминание «завтра меняется пачка» уже отправлено для ближайшей смены. */
    @Column(columnDefinition = "boolean default false")
    private boolean reminderSent;
}
