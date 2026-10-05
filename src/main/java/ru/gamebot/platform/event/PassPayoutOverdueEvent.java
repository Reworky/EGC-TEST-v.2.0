package ru.gamebot.platform.event;

import java.util.List;
import org.springframework.context.ApplicationEvent;

/** Заявки на вывод подписчиков EGC Pass ждут дольше обещанных PassPayoutSlaService.SLA_HOURS часов: админам и модераторам уходит напоминание. */
public class PassPayoutOverdueEvent extends ApplicationEvent {

    private final List<Long> requestIds;

    public PassPayoutOverdueEvent(Object source, List<Long> requestIds) {
        super(source);
        this.requestIds = requestIds;
    }

    public List<Long> getRequestIds() { return requestIds; }
}
