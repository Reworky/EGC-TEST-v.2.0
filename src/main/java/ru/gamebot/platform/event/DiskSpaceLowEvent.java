package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Диск сервера заполнен выше порога (по данным агента выкладки) - админов нужно предупредить (GamePlatformBot.onDiskSpaceLow). */
public class DiskSpaceLowEvent extends ApplicationEvent {

    private final int percent;

    public DiskSpaceLowEvent(Object source, int percent) {
        super(source);
        this.percent = percent;
    }

    public int getPercent() { return percent; }
}
