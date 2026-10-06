package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Рефереру начислен разовый бонус за первый одобренный квест приглашённого друга (2026-09-09). */
public class ReferrerFirstQuestBonusEvent extends ApplicationEvent {

    private final Long referrerTelegramId;
    private final String friendNickname;
    private final long bonusExc;
    private final int passDaysGranted;

    public ReferrerFirstQuestBonusEvent(Object source, Long referrerTelegramId, String friendNickname, long bonusExc, int passDaysGranted) {
        super(source);
        this.referrerTelegramId = referrerTelegramId;
        this.friendNickname = friendNickname;
        this.bonusExc = bonusExc;
        this.passDaysGranted = passDaysGranted;
    }

    public Long getReferrerTelegramId() { return referrerTelegramId; }
    public String getFriendNickname() { return friendNickname; }
    public long getBonusExc() { return bonusExc; }
    /** Сколько дней EGC Pass начислено за первого друга (0 - не начислялось: уже получал или у игрока активна подписка). */
    public int getPassDaysGranted() { return passDaysGranted; }
}
