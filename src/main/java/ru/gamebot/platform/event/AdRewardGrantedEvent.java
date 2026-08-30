package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

public class AdRewardGrantedEvent extends ApplicationEvent {

    private final Long userId;
    private final long excGranted;

    public AdRewardGrantedEvent(Object source, Long userId, long excGranted) {
        super(source);
        this.userId = userId;
        this.excGranted = excGranted;
    }

    public Long getUserId() { return userId; }
    public long getExcGranted() { return excGranted; }
}
