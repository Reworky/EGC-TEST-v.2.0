package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Создан черновик автоматического поста для канала - админам уходит карточка «на согласование» (GamePlatformBot.onChannelPostDraft). */
public class ChannelPostDraftEvent extends ApplicationEvent {

    private final Long draftId;

    public ChannelPostDraftEvent(Object source, Long draftId) {
        super(source);
        this.draftId = draftId;
    }

    public Long getDraftId() { return draftId; }
}
