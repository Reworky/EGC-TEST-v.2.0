package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

/** Задания дня для мини-аппа: три отметки за сегодня, получен ли «Сундук заданий» и что в нём лежит. */
@Data
@Builder
public class DailyTasksDto {
    /** false - функция выключена настройкой daily_tasks.enabled, карточку не показываем. */
    private boolean enabled;
    private boolean bonusDone;
    private boolean questDone;
    private boolean chestDone;
    private int doneCount;
    /** Сундук заданий за сегодня уже получен. */
    private boolean claimed;
    /** Все три выполнены и сундук ещё не получен - можно нажимать «Забрать». */
    private boolean ready;
    /** У игрока ещё нет выполненных квестов: задание называется «Выполни первый квест». */
    private boolean firstQuest;
    private long rewardExc;
    private int rewardTickets;
}
