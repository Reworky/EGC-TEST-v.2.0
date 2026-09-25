package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;
import ru.gamebot.platform.domain.model.Tournament;

/** Авто-продолженный турнир Clash Royale попал на возможную границу сезона (сброс трофеев около 1-го числа) - админам уходит
 *  предупреждение с готовым HTML-текстом. См. TournamentService.autoCreateNextTournament / ClashRoyaleSeasonGuard. */
public class TournamentSeasonWarningEvent extends ApplicationEvent {

    private final Tournament tournament;
    private final String warningHtml;

    public TournamentSeasonWarningEvent(Object source, Tournament tournament, String warningHtml) {
        super(source);
        this.tournament = tournament;
        this.warningHtml = warningHtml;
    }

    public Tournament getTournament() { return tournament; }
    public String getWarningHtml() { return warningHtml; }
}
