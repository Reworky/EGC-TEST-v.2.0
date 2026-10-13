package ru.gamebot.platform.service;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.stereotype.Service;

/** График работы техподдержки: ежедневно с 05:00 до 20:00 по Москве (с 20:00 до 05:00 — нерабочее время).
 *  В нерабочее время обращение принимается как обычно, но игрок получает уведомление, когда ждать ответ. */
@Service
public class SupportHoursService {

    public static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final LocalTime OPENS_AT = LocalTime.of(5, 0);
    private static final LocalTime CLOSES_AT = LocalTime.of(20, 0);

    public boolean isOpen() {
        return isOpen(ZonedDateTime.now(ZONE));
    }

    boolean isOpen(ZonedDateTime moment) {
        LocalTime t = moment.withZoneSameInstant(ZONE).toLocalTime();
        return !t.isBefore(OPENS_AT) && t.isBefore(CLOSES_AT);
    }

    public String scheduleText() {
        return "ежедневно с 05:00 до 20:00 по МСК";
    }

    /** Уведомление для бота (HTML). */
    public String afterHoursNotice() {
        return "🌙 <b>Сейчас поддержка не работает</b>\n"
                + "Мы отвечаем " + scheduleText() + ". Ваше сообщение получено — ответим, как только начнётся рабочий день (с 05:00 по МСК).";
    }

    /** То же без разметки — для мини-аппа. */
    public String afterHoursNoticePlain() {
        return "🌙 Сейчас поддержка не работает. Мы отвечаем " + scheduleText() + ". Ваше обращение получено — ответим, как только начнётся рабочий день (с 05:00 по МСК).";
    }
}
