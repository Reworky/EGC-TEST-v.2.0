package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;
import ru.gamebot.platform.domain.model.Poll;

public class PollClosedEvent extends ApplicationEvent {

    private final Poll poll;

    public PollClosedEvent(Object source, Poll poll) {
        super(source);
        this.poll = poll;
    }

    public Poll getPoll() {
        return poll;
    }
}
