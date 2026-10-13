package ru.gamebot.platform.service;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.stereotype.Service;

/** График работы техподдержки: ежедневно с 09:00 до 00:00 по Москве (с 00:00 до 09:00 — нерабочее время).
 *  В нерабочее время обращение принимается как обычно, но игрок получает уведомление, когда ждать ответ. */
@Service
public class SupportHoursService {

    public static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    /** Начало рабочего дня; работаем до полуночи. */
    private static final LocalTime OPENS_AT = LocalTime.of(9, 0);

    public boolean isOpen() {
        return isOpen(ZonedDateTime.now(ZONE));
    }

    boolean isOpen(ZonedDateTime moment) {
        return !moment.withZoneSameInstant(ZONE).toLocalTime().isBefore(OPENS_AT);
    }

    public String scheduleText() {
        return "ежедневно с 09:00 до 00:00 по МСК";
    }

    /** Уведомление для бота (HTML). */
    public String afterHoursNotice() {
        return "🌙 <b>Сейчас поддержка не работает</b>\n"
                + "Мы отвечаем " + scheduleText() + ". Ваше сообщение получено — ответим, как только начнётся рабочий день (с 09:00 по МСК).";
    }

    /** То же без разметки — для мини-аппа. */
    public String afterHoursNoticePlain() {
        return "🌙 Сейчас поддержка не работает. Мы отвечаем " + scheduleText() + ". Ваше обращение получено — ответим, как только начнётся рабочий день (с 09:00 по МСК).";
    }
}
