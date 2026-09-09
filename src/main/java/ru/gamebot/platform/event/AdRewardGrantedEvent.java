package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

public class AdRewardGrantedEvent extends ApplicationEvent {

    private final Long userId;
    private final long excGranted;
    private final long milestoneBonus;

    public AdRewardGrantedEvent(Object source, Long userId, long excGranted, long milestoneBonus) {
        super(source);
        this.userId = userId;
        this.excGranted = excGranted;
        this.milestoneBonus = milestoneBonus;
    }

    public Long getUserId() { return userId; }
    public long getExcGranted() { return excGranted; }
    public long getMilestoneBonus() { return milestoneBonus; }
}
