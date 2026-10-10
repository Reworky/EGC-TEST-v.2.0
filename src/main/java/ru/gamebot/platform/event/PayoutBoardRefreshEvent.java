package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Пора обновить живое закреплённое сообщение «Лента выплат» в канале клуба (GamePlatformBot.onPayoutBoardRefresh). */
public class PayoutBoardRefreshEvent extends ApplicationEvent {
    public PayoutBoardRefreshEvent(Object source) {
        super(source);
    }
}
