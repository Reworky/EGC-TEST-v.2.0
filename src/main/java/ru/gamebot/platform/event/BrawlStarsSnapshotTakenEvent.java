package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;
import ru.gamebot.platform.domain.model.TournamentEntry;

public class BrawlStarsSnapshotTakenEvent extends ApplicationEvent {

    private final TournamentEntry entry;

    public BrawlStarsSnapshotTakenEvent(Object source, TournamentEntry entry) {
        super(source);
        this.entry = entry;
    }

    public TournamentEntry getEntry() { return entry; }
}
