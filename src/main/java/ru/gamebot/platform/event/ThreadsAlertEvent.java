package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Сообщение админам от автопубликации в Threads (сбой, истекающий токен, первые публикации). Текст уже в HTML Telegram (GamePlatformBot.onThreadsAlert). */
public class ThreadsAlertEvent extends ApplicationEvent {

    private final String text;

    public ThreadsAlertEvent(Object source, String text) {
        super(source);
        this.text = text;
    }

    public String getText() { return text; }
}
