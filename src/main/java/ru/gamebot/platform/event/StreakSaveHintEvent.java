package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Подписчик EGC Pass пропустил вчера: если зайдёт сегодня, серию бесплатно сохранит Pass (вместо сообщения «серия прервалась»). */
public class StreakSaveHintEvent extends ApplicationEvent {

    private final Long telegramId;
    private final int streakDays;

    public StreakSaveHintEvent(Object source, Long telegramId, int streakDays) {
        super(source);
        this.telegramId = telegramId;
        this.streakDays = streakDays;
    }

    public Long getTelegramId() { return telegramId; }
    public int getStreakDays() { return streakDays; }
}
