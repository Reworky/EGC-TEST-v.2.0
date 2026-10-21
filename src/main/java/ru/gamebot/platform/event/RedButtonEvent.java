package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Этап жизни «красной кнопки» (RedButtonService -> GamePlatformBot.onRedButtonEvent): предупредить админов, опубликовать, закрыть пост, сообщить о сбое. */
public class RedButtonEvent extends ApplicationEvent {

    public enum Kind { WARN, PUBLISH, CLOSE, ALERT }

    private final Kind kind;
    private final Long scheduleId;
    private final String message;

    public RedButtonEvent(Object source, Kind kind, Long scheduleId, String message) {
        super(source);
        this.kind = kind;
        this.scheduleId = scheduleId;
        this.message = message;
    }

    public Kind getKind() { return kind; }

    public Long getScheduleId() { return scheduleId; }

    public String getMessage() { return message; }
}
