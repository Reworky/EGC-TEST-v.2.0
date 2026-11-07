package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Тревога о связке EGC VPN для техника (Влад): VPN-бот шлёт её тем, кто указан в VPN2_TECH_IDS. Админам та же тревога уходит отдельно через AnalyticsAlertEvent. */
public class Vpn2TechAlertEvent extends ApplicationEvent {

    private final String textHtml;

    public Vpn2TechAlertEvent(Object source, String textHtml) {
        super(source);
        this.textHtml = textHtml;
    }

    public String getTextHtml() { return textHtml; }
}
