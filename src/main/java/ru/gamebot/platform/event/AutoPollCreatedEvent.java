package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;
import ru.gamebot.platform.domain.model.Poll;

public class AutoPollCreatedEvent extends ApplicationEvent {

    private final Poll poll;

    public AutoPollCreatedEvent(Object source, Poll poll) {
        super(source);
        this.poll = poll;
    }

    public Poll getPoll() { return poll; }
}
