package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

public class CooldownExpiredEvent extends ApplicationEvent {

    private final Long telegramId;
    private final String gameName;
    private final String questTitle; // null = игровой кулдаун, non-null = кулдаун конкретного квеста

    public CooldownExpiredEvent(Object source, Long telegramId, String gameName, String questTitle) {
        super(source);
        this.telegramId = telegramId;
        this.gameName = gameName;
        this.questTitle = questTitle;
    }

    public Long getTelegramId() { return telegramId; }
    public String getGameName() { return gameName; }
    public String getQuestTitle() { return questTitle; }
    public boolean isQuestSpecific() { return questTitle != null; }
}
