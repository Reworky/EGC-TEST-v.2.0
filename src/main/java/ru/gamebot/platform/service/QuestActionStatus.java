package ru.gamebot.platform.service;

/** Результат попытки взять квест или отправить отчёт через API (Mini App). Те же правила, что и в боте. */
public enum QuestActionStatus {
    OK,
    QUEST_INACTIVE,
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
    HAS_PENDING_REPORT,
    EXPIRED,
    NEEDS_BRAWL_TAG,
    NEEDS_CLASH_TAG,
    NEEDS_CLASH_ROYALE_TAG,
    NEEDS_DOTA_LINK,
    NEEDS_CS2_LINK,
    AUTO_VERIFIED_NO_REPORT,
    NEEDS_BRAWL_PARTNER,
    /** Квест с лимитом участников (participantLimit) уже набрал максимум одобренных заявок —
     * раньше это было IllegalArgumentException из createDraftSubmission, которое нигде не ловилось
     * в takeQuestChecked и улетало до глобального обработчика ("Что-то пошло не так", 2026-09-15). */
    PARTICIPANT_LIMIT_REACHED,
    /** Подписка на канал теперь проверяется точечно при взятии квеста (2026-09-14), а не сразу
     *  после анкеты — см. GamePlatformBot.handleTakeQuest. Мини-апп должен запретить взятие квеста
     *  тем же образом, иначе через API можно обойти проверку, которая есть в боте. */
    NEEDS_CHANNEL_SUBSCRIPTION
}
