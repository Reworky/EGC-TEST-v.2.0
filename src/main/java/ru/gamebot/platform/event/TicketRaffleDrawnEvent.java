package ru.gamebot.platform.event;

import java.util.List;
import org.springframework.context.ApplicationEvent;
import ru.gamebot.platform.domain.model.AppUser;

/** Ежедневный розыгрыш билетов колеса фортуны среди тех, кто заходил сегодня — см.
 *  WeeklyResetScheduler.drawDailyTicketRaffle(). Билеты уже начислены победителям к моменту публикации
 *  события; сюда попадает только для рассылки личных уведомлений и подготовки поста в канал. */
public class TicketRaffleDrawnEvent extends ApplicationEvent {

    private final List<AppUser> winners;

    public TicketRaffleDrawnEvent(Object source, List<AppUser> winners) {
        super(source);
        this.winners = winners;
    }

    public List<AppUser> getWinners() { return winners; }
}
