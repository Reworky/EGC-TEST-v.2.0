package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

public class ReferralFriendInactiveEvent extends ApplicationEvent {

    private final Long referrerTelegramId;
    private final String friendNickname;

    public ReferralFriendInactiveEvent(Object source, Long referrerTelegramId, String friendNickname) {
        super(source);
        this.referrerTelegramId = referrerTelegramId;
        this.friendNickname = friendNickname;
    }

    public Long getReferrerTelegramId() { return referrerTelegramId; }
    public String getFriendNickname() { return friendNickname; }
}
