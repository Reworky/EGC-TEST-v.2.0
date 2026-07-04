package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;
import ru.gamebot.platform.domain.model.Tournament;
import ru.gamebot.platform.domain.model.TournamentEntry;

import java.util.List;

public class TournamentFinishedEvent extends ApplicationEvent {

    private final Tournament tournament;
    private final List<TournamentEntry> entries;

    public TournamentFinishedEvent(Object source, Tournament tournament, List<TournamentEntry> entries) {
        super(source);
        this.tournament = tournament;
        this.entries = entries;
    }

    public Tournament getTournament() { return tournament; }
    public List<TournamentEntry> getEntries() { return entries; }
}
