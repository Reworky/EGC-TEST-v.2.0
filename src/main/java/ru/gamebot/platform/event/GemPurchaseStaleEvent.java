package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Заявка на донат висит без ответа дольше порога - напоминание админам/модераторам (один раз на заявку). */
public class GemPurchaseStaleEvent extends ApplicationEvent {

    private final Long requestId;
    private final long hoursWaiting;

    public GemPurchaseStaleEvent(Object source, Long requestId, long hoursWaiting) {
        super(source);
        this.requestId = requestId;
        this.hoursWaiting = hoursWaiting;
    }

    public Long getRequestId() { return requestId; }
    public long getHoursWaiting() { return hoursWaiting; }
}
