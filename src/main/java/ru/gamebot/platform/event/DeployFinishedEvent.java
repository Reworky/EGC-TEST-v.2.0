package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;
import ru.gamebot.platform.service.DeployService;

/** Агент выкладки завершил запуск (успех, сбой сборки или автооткат) - бот сообщает инициатору. */
public class DeployFinishedEvent extends ApplicationEvent {

    private final DeployService.Status status;

    public DeployFinishedEvent(Object source, DeployService.Status status) {
        super(source);
        this.status = status;
    }

    public DeployService.Status getStatus() { return status; }
}
