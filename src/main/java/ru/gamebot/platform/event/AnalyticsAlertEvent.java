package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Сработало правило алерта по метрике (AlertService.check): готовый HTML-текст для личного сообщения админам. */
public class AnalyticsAlertEvent extends ApplicationEvent {

    private final String textHtml;

    public AnalyticsAlertEvent(Object source, String textHtml) {
        super(source);
        this.textHtml = textHtml;
    }

    public String getTextHtml() { return textHtml; }
}
