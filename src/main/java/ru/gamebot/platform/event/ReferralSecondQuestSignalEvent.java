package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Приглашённый друг выполнил ровно ВТОРОЙ одобренный квест — чисто информационный сигнал рефереру,
 *  без начисления (разовый бонус уже выплачен на первом, см. ReferrerFirstQuestBonusEvent; 10%-е
 *  отчисления с квестов друга продолжают идти как обычно). Усиливает видимость уже существующей
 *  механики отчислений — "друг не разово прошёл один квест, а реально втянулся" (аудит вовлечённости,
 *  2026-09-14). */
public class ReferralSecondQuestSignalEvent extends ApplicationEvent {

    private final Long referrerTelegramId;
    private final String friendNickname;

    public ReferralSecondQuestSignalEvent(Object source, Long referrerTelegramId, String friendNickname) {
        super(source);
        this.referrerTelegramId = referrerTelegramId;
        this.friendNickname = friendNickname;
    }

    public Long getReferrerTelegramId() { return referrerTelegramId; }
    public String getFriendNickname() { return friendNickname; }
}
