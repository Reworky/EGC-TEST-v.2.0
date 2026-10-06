package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Приглашённый друг зашёл, но за 48 часов не выполнил первый квест - напоминание пригласившему. */
public class ReferredFriendStalledEvent extends ApplicationEvent {

    private final Long referrerTelegramId;
    private final String friendNickname;
    private final String friendUsername;

    public ReferredFriendStalledEvent(Object source, Long referrerTelegramId, String friendNickname, String friendUsername) {
        super(source);
        this.referrerTelegramId = referrerTelegramId;
        this.friendNickname = friendNickname;
        this.friendUsername = friendUsername;
    }

    public Long getReferrerTelegramId() { return referrerTelegramId; }
    public String getFriendNickname() { return friendNickname; }
    /** Telegram-юзернейм друга (без @) или null. */
    public String getFriendUsername() { return friendUsername; }
}
