package ru.gamebot.platform.event;

import java.util.List;
import org.springframework.context.ApplicationEvent;

/** Итоги лиг за закончившуюся неделю (публикуется при сбросе недельного XP, до обнуления): сколько игроков в каждой лиге и сколько выплачено. */
public class LeagueWeekEvent extends ApplicationEvent {

    public record LeagueRow(String displayName, int minWeeklyXp, long excPrize, int players) {}

    private final List<LeagueRow> rows;
    private final int activePlayers;
    private final long totalPrize;

    public LeagueWeekEvent(Object source, List<LeagueRow> rows, int activePlayers, long totalPrize) {
        super(source);
        this.rows = rows;
        this.activePlayers = activePlayers;
        this.totalPrize = totalPrize;
    }

    /** По одной строке на лигу, от высшей к низшей; players - только игроки с XP за неделю больше нуля. */
    public List<LeagueRow> getRows() { return rows; }

    public int getActivePlayers() { return activePlayers; }

    public long getTotalPrize() { return totalPrize; }
}
