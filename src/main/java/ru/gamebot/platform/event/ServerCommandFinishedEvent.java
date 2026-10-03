package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Агент выполнил справочное действие раздела «🖥 Сервер» (status / logs / errors / restart) - бот присылает ответ. */
public class ServerCommandFinishedEvent extends ApplicationEvent {

    private final String action;
    private final long requestedBy;
    private final boolean ok;
    private final String message;
    private final String output;

    public ServerCommandFinishedEvent(Object source, String action, long requestedBy, boolean ok, String message, String output) {
        super(source);
        this.action = action;
        this.requestedBy = requestedBy;
        this.ok = ok;
        this.message = message;
        this.output = output;
    }

    public String getAction() { return action; }
    public long getRequestedBy() { return requestedBy; }
    public boolean isOk() { return ok; }
    public String getMessage() { return message; }
    public String getOutput() { return output; }
}
