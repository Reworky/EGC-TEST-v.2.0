package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;
import ru.gamebot.platform.domain.model.Tournament;
import ru.gamebot.platform.domain.model.TournamentEntry;

import java.util.List;

public class TournamentCancelledEvent extends ApplicationEvent {

    private final Tournament tournament;
    private final List<TournamentEntry> refundedEntries;

    public TournamentCancelledEvent(Object source, Tournament tournament, List<TournamentEntry> refundedEntries) {
        super(source);
        this.tournament = tournament;
        this.refundedEntries = refundedEntries;
    }

    public Tournament getTournament() { return tournament; }
    public List<TournamentEntry> getRefundedEntries() { return refundedEntries; }
}
