package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Бюджет спонсорской кампании выбран до конца: кампания и её квесты выключены, админов нужно предупредить (GamePlatformBot.onSponsorBudgetExhausted). */
public class SponsorBudgetExhaustedEvent extends ApplicationEvent {

    private final Long sponsorId;
    private final String sponsorName;
    private final long budgetExc;
    private final long spentExc;

    public SponsorBudgetExhaustedEvent(Object source, Long sponsorId, String sponsorName, long budgetExc, long spentExc) {
        super(source);
        this.sponsorId = sponsorId;
        this.sponsorName = sponsorName;
        this.budgetExc = budgetExc;
        this.spentExc = spentExc;
    }

    public Long getSponsorId() { return sponsorId; }
    public String getSponsorName() { return sponsorName; }
    public long getBudgetExc() { return budgetExc; }
    public long getSpentExc() { return spentExc; }
}
