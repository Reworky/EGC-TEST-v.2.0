package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Пул квестов не растёт, пока игроков и выполнений становится больше — см. QuestPoolHealthService.weeklyCheck().
 *  Несёт готовый HTML-текст отчёта для админов. */
public class QuestPoolStaleEvent extends ApplicationEvent {

    private final String reportHtml;

    public QuestPoolStaleEvent(Object source, String reportHtml) {
        super(source);
        this.reportHtml = reportHtml;
    }

    public String getReportHtml() { return reportHtml; }
}
