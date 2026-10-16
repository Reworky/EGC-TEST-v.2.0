package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Приглашённому игроку выплачен бонус за приглашение (после N квестов и M дней, 2026-10-16): бот отправляет ему сообщение. */
public class ReferralWelcomeBonusPaidEvent extends ApplicationEvent {

    private final Long invitedTelegramId;
    private final long bonusExc;

    public ReferralWelcomeBonusPaidEvent(Object source, Long invitedTelegramId, long bonusExc) {
        super(source);
        this.invitedTelegramId = invitedTelegramId;
        this.bonusExc = bonusExc;
    }

    public Long getInvitedTelegramId() { return invitedTelegramId; }
    public long getBonusExc() { return bonusExc; }
}
