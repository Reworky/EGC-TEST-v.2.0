package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Сообщение неактивному игроку с обещанием бонуса за возвращение. excOffered — сумма, которая придёт ПОСЛЕ
 * возвращения и первого одобренного квеста (см. UserService.claimDormancyReturnBonus), а не уже начисленная. */
public class DormancyReengagementEvent extends ApplicationEvent {

    private final Long telegramId;
    private final int tier;
    private final long daysSinceActive;
    private final long excOffered;

    public DormancyReengagementEvent(Object source, Long telegramId,
                                      int tier, long daysSinceActive, long excOffered) {
        super(source);
        this.telegramId = telegramId;
        this.tier = tier;
        this.daysSinceActive = daysSinceActive;
        this.excOffered = excOffered;
    }

    public Long getTelegramId() { return telegramId; }
    public int getTier() { return tier; }
    public long getDaysSinceActive() { return daysSinceActive; }
    public long getExcOffered() { return excOffered; }
}
