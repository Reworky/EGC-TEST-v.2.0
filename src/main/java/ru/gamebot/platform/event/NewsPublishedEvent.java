package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

public class NewsPublishedEvent extends ApplicationEvent {

    private final String title;
    private final String body;

    public NewsPublishedEvent(Object source, String title, String body) {
        super(source);
        this.title = title;
        this.body = body;
    }

    public String getTitle() { return title; }
    public String getBody() { return body; }
}
