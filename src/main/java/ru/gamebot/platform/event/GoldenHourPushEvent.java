package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Напоминание игроку о золотом часе (GoldenHourService, 2026-10-17): startHourMsk/endHourMsk - окно по Москве, percent - бонус к EXC, maxQuests - на сколько квестов. */
public class GoldenHourPushEvent extends ApplicationEvent {

    private final Long telegramId;
    private final int startHourMsk;
    private final int endHourMsk;
    private final int percent;
    private final int maxQuests;

    public GoldenHourPushEvent(Object source, Long telegramId, int startHourMsk, int endHourMsk, int percent, int maxQuests) {
        super(source);
        this.telegramId = telegramId;
        this.startHourMsk = startHourMsk;
        this.endHourMsk = endHourMsk;
        this.percent = percent;
        this.maxQuests = maxQuests;
    }

    public Long getTelegramId() { return telegramId; }
    public int getStartHourMsk() { return startHourMsk; }
    public int getEndHourMsk() { return endHourMsk; }
    public int getPercent() { return percent; }
    public int getMaxQuests() { return maxQuests; }
}
