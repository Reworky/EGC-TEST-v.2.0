package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

public class DormancyReengagementEvent extends ApplicationEvent {

    private final Long telegramId;
    private final int tier;
    private final long daysSinceActive;
    private final long excGranted;

    public DormancyReengagementEvent(Object source, Long telegramId,
                                      int tier, long daysSinceActive, long excGranted) {
        super(source);
        this.telegramId = telegramId;
        this.tier = tier;
        this.daysSinceActive = daysSinceActive;
        this.excGranted = excGranted;
    }

    public Long getTelegramId() { return telegramId; }
    public int getTier() { return tier; }
    public long getDaysSinceActive() { return daysSinceActive; }
    public long getExcGranted() { return excGranted; }
}
