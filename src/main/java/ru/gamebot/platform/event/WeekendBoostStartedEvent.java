package ru.gamebot.platform.event;

import java.time.LocalDateTime;
import org.springframework.context.ApplicationEvent;

/** Глобальный буст EXC-наград за квесты запущен — см. WeeklyResetScheduler.startWeekendBoost().
 *  Буст уже активен к моменту публикации события (награды за одобренные квесты уже умножаются) —
 *  сюда попадает только для подготовки анонса в канал (через согласование администратора). */
public class WeekendBoostStartedEvent extends ApplicationEvent {

    private final int boostPercent;
    private final LocalDateTime endAt;

    public WeekendBoostStartedEvent(Object source, int boostPercent, LocalDateTime endAt) {
        super(source);
        this.boostPercent = boostPercent;
        this.endAt = endAt;
    }

    public int getBoostPercent() { return boostPercent; }
    public LocalDateTime getEndAt() { return endAt; }
}
