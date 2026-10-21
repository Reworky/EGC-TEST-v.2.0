package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Затраты на квесты сезонного события достигли 80% или 100% потолка (SeasonalEventService): админов нужно предупредить (GamePlatformBot.onEventBudgetAlert). */
public class EventBudgetAlertEvent extends ApplicationEvent {

    private final String eventCode;
    private final String title;
    private final int percent;
    private final long usedExc;
    private final long budgetExc;

    public EventBudgetAlertEvent(Object source, String eventCode, String title, int percent, long usedExc, long budgetExc) {
        super(source);
        this.eventCode = eventCode;
        this.title = title;
        this.percent = percent;
        this.usedExc = usedExc;
        this.budgetExc = budgetExc;
    }

    public String getEventCode() { return eventCode; }
    public String getTitle() { return title; }
    public int getPercent() { return percent; }
    public long getUsedExc() { return usedExc; }
    public long getBudgetExc() { return budgetExc; }
}
