package ru.gamebot.platform.event;

import java.time.LocalDate;
import org.springframework.context.ApplicationEvent;

/** Напоминание игроку по партнёрской миссии «ОТП Карта» (2026-11-04). kind: 1 - «миссия ждёт тебя», 2 - «3 дня до конца окна на покупку», 3 - «банк мог заплатить». */
public class OtpMissionReminderEvent extends ApplicationEvent {

    public static final int KIND_WAITING = 1;
    public static final int KIND_DEADLINE = 2;
    public static final int KIND_BANK_DAY = 3;

    private final Long telegramId;
    private final Long submissionId;
    private final int kind;
    private final LocalDate date;

    public OtpMissionReminderEvent(Object source, Long telegramId, Long submissionId, int kind, LocalDate date) {
        super(source);
        this.telegramId = telegramId;
        this.submissionId = submissionId;
        this.kind = kind;
        this.date = date;
    }

    public Long getTelegramId() { return telegramId; }
    public Long getSubmissionId() { return submissionId; }
    public int getKind() { return kind; }
    /** Для kind 2 - последний день окна на покупку, для kind 3 - дата выплаты банка; для kind 1 - null. */
    public LocalDate getDate() { return date; }
}
