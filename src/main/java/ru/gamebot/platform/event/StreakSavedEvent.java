package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Подписчику EGC Pass бесплатно сохранили серию входов (пропущен один день, раз в месяц). */
public class StreakSavedEvent extends ApplicationEvent {

    private final Long telegramId;
    private final int streakDays;

    public StreakSavedEvent(Object source, Long telegramId, int streakDays) {
        super(source);
        this.telegramId = telegramId;
        this.streakDays = streakDays;
    }

    public Long getTelegramId() { return telegramId; }
    public int getStreakDays() { return streakDays; }
}
