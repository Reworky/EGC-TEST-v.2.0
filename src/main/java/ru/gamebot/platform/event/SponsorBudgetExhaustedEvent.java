package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Спонсорская кампания завершена автоматически (бюджет выбран или набран оплаченный лимит подписчиков): кампания и её квесты выключены, админов нужно предупредить (GamePlatformBot.onSponsorBudgetExhausted). */
public class SponsorBudgetExhaustedEvent extends ApplicationEvent {

    private final Long sponsorId;
    private final String sponsorName;
    private final long budgetExc;
    private final long spentExc;
    private final String reason;

    public SponsorBudgetExhaustedEvent(Object source, Long sponsorId, String sponsorName, long budgetExc, long spentExc, String reason) {
        super(source);
        this.sponsorId = sponsorId;
        this.sponsorName = sponsorName;
        this.budgetExc = budgetExc;
        this.spentExc = spentExc;
        this.reason = reason;
    }

    public Long getSponsorId() { return sponsorId; }
    public String getSponsorName() { return sponsorName; }
    public long getBudgetExc() { return budgetExc; }
    public long getSpentExc() { return spentExc; }
    public String getReason() { return reason; }
}
