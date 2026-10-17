package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Короткая рассылка игроку из PlatformPushService (2026-10-17): готовый HTML-текст и одна кнопка. Если задан gameName - кнопка ведёт к квестам этой игры
 *  (токен игры строит бот), иначе - на buttonCallback. */
public class PlayerPushEvent extends ApplicationEvent {

    private final Long telegramId;
    private final String html;
    private final String buttonText;
    private final String buttonCallback;
    private final String gameName;

    public PlayerPushEvent(Object source, Long telegramId, String html, String buttonText, String buttonCallback, String gameName) {
        super(source);
        this.telegramId = telegramId;
        this.html = html;
        this.buttonText = buttonText;
        this.buttonCallback = buttonCallback;
        this.gameName = gameName;
    }

    public Long getTelegramId() { return telegramId; }
    public String getHtml() { return html; }
    public String getButtonText() { return buttonText; }
    public String getButtonCallback() { return buttonCallback; }
    public String getGameName() { return gameName; }
}
