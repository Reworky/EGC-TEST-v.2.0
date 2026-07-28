package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

public class WeeklyDigestInactiveEvent extends ApplicationEvent {

    private final Long telegramId;
    private final long newQuestsCount;
    private final long totalSpinsCount;

    public WeeklyDigestInactiveEvent(Object source, Long telegramId,
                                      long newQuestsCount, long totalSpinsCount) {
        super(source);
        this.telegramId = telegramId;
        this.newQuestsCount = newQuestsCount;
        this.totalSpinsCount = totalSpinsCount;
    }

    public Long getTelegramId() { return telegramId; }
    public long getNewQuestsCount() { return newQuestsCount; }
    public long getTotalSpinsCount() { return totalSpinsCount; }
}
