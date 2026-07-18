package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

public class CooldownExpiredEvent extends ApplicationEvent {

    private final Long telegramId;
    private final String gameName;

    public CooldownExpiredEvent(Object source, Long telegramId, String gameName) {
        super(source);
        this.telegramId = telegramId;
        this.gameName = gameName;
    }

    public Long getTelegramId() { return telegramId; }
    public String getGameName() { return gameName; }
}
