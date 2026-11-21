package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Пришёл постбек LeadGid: сообщить модераторам и админам (слушает GamePlatformBot). */
public class LeadgidConversionEvent extends ApplicationEvent {

    private final Long conversionId;

    public LeadgidConversionEvent(Object source, Long conversionId) {
        super(source);
        this.conversionId = conversionId;
    }

    public Long getConversionId() { return conversionId; }
}
