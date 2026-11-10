package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Напоминание игроку по партнёрскому квесту банка, который ведёт менеджер (10.11.2026). kind: 1 - взял, но не нажал «Начать»; 2 - нажал «Начать», первая часть не проверена; 3 - последнее напоминание. */
public class PartnerQuestNudgeEvent extends ApplicationEvent {

    public static final int KIND_NO_CLICK = 1;
    public static final int KIND_ASK = 2;
    public static final int KIND_LAST = 3;

    private final Long telegramId;
    private final Long submissionId;
    private final int kind;

    public PartnerQuestNudgeEvent(Object source, Long telegramId, Long submissionId, int kind) {
        super(source);
        this.telegramId = telegramId;
        this.submissionId = submissionId;
        this.kind = kind;
    }

    public Long getTelegramId() { return telegramId; }
    public Long getSubmissionId() { return submissionId; }
    public int getKind() { return kind; }
}
