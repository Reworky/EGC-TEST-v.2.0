package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;
import ru.gamebot.platform.domain.model.Tournament;

public class BrawlStarsSnapshotBatchFailedEvent extends ApplicationEvent {

    private final Tournament tournament;
    private final String phase;

    public BrawlStarsSnapshotBatchFailedEvent(Object source, Tournament tournament, String phase) {
        super(source);
        this.tournament = tournament;
        this.phase = phase;
    }

    public Tournament getTournament() { return tournament; }
    public String getPhase() { return phase; }
}
