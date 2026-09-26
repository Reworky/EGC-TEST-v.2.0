package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Серия входов прервалась (игрок не зашёл вчера по UTC) - сообщение «не успел» с предложением восстановить (WeeklyResetScheduler.checkStreaksBroken). */
public class StreakBrokenEvent extends ApplicationEvent {

    private final Long telegramId;
    private final int lostDays;

    public StreakBrokenEvent(Object source, Long telegramId, int lostDays) {
        super(source);
        this.telegramId = telegramId;
        this.lostDays = lostDays;
    }

    public Long getTelegramId() { return telegramId; }
    public int getLostDays() { return lostDays; }
}
