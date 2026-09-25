package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;
import ru.gamebot.platform.domain.model.Tournament;

/** Этап жизни турнира, о котором нужно подготовить пост для канала на одобрение админу: открылась регистрация или турнир
 *  стартовал (регистрация закрыта). Итоги турнира идут отдельным событием TournamentFinishedEvent. См.
 *  TournamentService.announceTournamentStages(). */
public class TournamentStageEvent extends ApplicationEvent {

    public enum Stage { REGISTRATION_OPENED, TOURNAMENT_STARTED }

    private final Tournament tournament;
    private final Stage stage;

    public TournamentStageEvent(Object source, Tournament tournament, Stage stage) {
        super(source);
        this.tournament = tournament;
        this.stage = stage;
    }

    public Tournament getTournament() { return tournament; }
    public Stage getStage() { return stage; }
}
