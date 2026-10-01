package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;
import ru.gamebot.platform.domain.model.Tournament;
import ru.gamebot.platform.domain.model.TournamentEntry;

import java.util.List;

public class TournamentFinishedEvent extends ApplicationEvent {

    private final Tournament tournament;
    private final List<TournamentEntry> entries;
    /** Авто-продолжение, созданное TournamentService.autoCreateNextTournament сразу при сеттле этого турнира
     *  (null, если продолжение не создалось - см. autoCreateNextTournament). Нужно, чтобы итоги и анонс новой
     *  регистрации уходили ОДНИМ постом на согласование вместо двух отдельных карточек подряд (запрошено 2026-10-01). */
    private final Tournament nextTournament;

    public TournamentFinishedEvent(Object source, Tournament tournament, List<TournamentEntry> entries, Tournament nextTournament) {
        super(source);
        this.tournament = tournament;
        this.entries = entries;
        this.nextTournament = nextTournament;
    }

    public Tournament getTournament() { return tournament; }
    public List<TournamentEntry> getEntries() { return entries; }
    public Tournament getNextTournament() { return nextTournament; }
}
