package ru.gamebot.platform.service;

/** Результат попытки взять квест или отправить отчёт через API (Mini App). Те же правила, что и в боте. */
public enum QuestActionStatus {
    OK,
    ALREADY_DRAFT,
    ALREADY_PENDING,
    ALREADY_APPROVED,
    HAS_REJECTED_REPORT,
    NOT_TAKEN,
    SLOTS_FULL,
    SAME_QUEST_COOLDOWN,
    GAME_COOLDOWN,
    TAKE_COOLDOWN,
    REJECT_COOLDOWN,
    EXPIRED
}
