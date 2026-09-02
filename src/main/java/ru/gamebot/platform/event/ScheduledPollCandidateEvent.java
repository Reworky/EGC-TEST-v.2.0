package ru.gamebot.platform.event;

import java.util.List;
import org.springframework.context.ApplicationEvent;

public class ScheduledPollCandidateEvent extends ApplicationEvent {

    private final String question;
    private final List<String> options;

    public ScheduledPollCandidateEvent(Object source, String question, List<String> options) {
        super(source);
        this.question = question;
        this.options = options;
    }

    public String getQuestion() { return question; }
    public List<String> getOptions() { return options; }
}
