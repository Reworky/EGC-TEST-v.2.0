package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Игрок выполнил все три задания дня и может забрать «Сундук заданий» (2026-10-10). */
public class DailyTasksCompletedEvent extends ApplicationEvent {

    private final Long telegramId;

    public DailyTasksCompletedEvent(Object source, Long telegramId) {
        super(source);
        this.telegramId = telegramId;
    }

    public Long getTelegramId() { return telegramId; }
}
