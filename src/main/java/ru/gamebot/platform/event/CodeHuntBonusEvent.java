package ru.gamebot.platform.event;

import java.time.YearMonth;
import org.springframework.context.ApplicationEvent;
import ru.gamebot.platform.domain.model.AppUser;

/** Игрок поймал за месяц достаточно кодов дня и получил бонус к лимиту вывода (CodeHuntService) - его нужно уведомить (GamePlatformBot.onCodeHuntBonus). */
public class CodeHuntBonusEvent extends ApplicationEvent {

    private final AppUser user;
    private final int caught;
    private final int required;
    private final long bonusExc;
    private final YearMonth month;

    public CodeHuntBonusEvent(Object source, AppUser user, int caught, int required, long bonusExc, YearMonth month) {
        super(source);
        this.user = user;
        this.caught = caught;
        this.required = required;
        this.bonusExc = bonusExc;
        this.month = month;
    }

    public AppUser getUser() { return user; }
    public int getCaught() { return caught; }
    public int getRequired() { return required; }
    public long getBonusExc() { return bonusExc; }
    public YearMonth getMonth() { return month; }
}
