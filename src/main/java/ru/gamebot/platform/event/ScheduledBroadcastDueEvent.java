package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

public class ScheduledBroadcastDueEvent extends ApplicationEvent {

    private final Long broadcastId;

    public ScheduledBroadcastDueEvent(Object source, Long broadcastId) {
        super(source);
        this.broadcastId = broadcastId;
    }

    public Long getBroadcastId() { return broadcastId; }
}
