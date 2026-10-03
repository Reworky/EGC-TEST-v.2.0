package ru.gamebot.platform.event;

import java.util.List;
import org.springframework.context.ApplicationEvent;
import ru.gamebot.platform.domain.model.AppUser;

/** Игрок попытался вывести на реквизиты, которые уже использует другой аккаунт — заявка не создана, админов нужно
 *  предупредить (GamePlatformBot.onWithdrawalDestinationConflict). */
public class WithdrawalDestinationConflictEvent extends ApplicationEvent {

    private final AppUser user;
    private final List<AppUser> otherUsers;
    private final String destination;

    public WithdrawalDestinationConflictEvent(Object source, AppUser user, List<AppUser> otherUsers, String destination) {
        super(source);
        this.user = user;
        this.otherUsers = otherUsers;
        this.destination = destination;
    }

    public AppUser getUser() { return user; }
    public List<AppUser> getOtherUsers() { return otherUsers; }
    public String getDestination() { return destination; }
}
