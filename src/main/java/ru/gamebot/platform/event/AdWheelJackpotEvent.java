package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Игрок выиграл джекпот рекламного колеса - уведомление админам (AdWheelService.spin). */
public class AdWheelJackpotEvent extends ApplicationEvent {

    private final Long telegramId;
    private final String nickname;
    private final long excAmount;

    public AdWheelJackpotEvent(Object source, Long telegramId, String nickname, long excAmount) {
        super(source);
        this.telegramId = telegramId;
        this.nickname = nickname;
        this.excAmount = excAmount;
    }

    public Long getTelegramId() { return telegramId; }
    public String getNickname() { return nickname; }
    public long getExcAmount() { return excAmount; }
}
