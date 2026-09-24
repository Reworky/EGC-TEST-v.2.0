package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

public class DormancyBonusClaimedEvent extends ApplicationEvent {

    private final Long telegramId;
    private final long excGranted;

    public DormancyBonusClaimedEvent(Object source, Long telegramId, long excGranted) {
        super(source);
        this.telegramId = telegramId;
        this.excGranted = excGranted;
    }

    public Long getTelegramId() { return telegramId; }
    public long getExcGranted() { return excGranted; }
}
