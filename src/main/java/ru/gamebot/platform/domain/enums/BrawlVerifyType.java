package ru.gamebot.platform.domain.enums;

/** Типы авто-проверки Brawl Stars. BATTLES/PARTNER_BATTLES считаются по журналу боёв, NEW_BRAWLER — по появлению
 *  бойца в коллекции; TROPHIES и типы «прокачки» (BRAWLER_POWER…EXP_LEVEL) — по числу из профиля игрока:
 *  прогресс = текущее значение минус база, снятая при взятии квеста (Submission.brawlBaselineTrophies).
 *  Длина имён не больше 20 символов — колонка quests.brawl_verify_type = varchar(20). */
public enum BrawlVerifyType {
    TROPHIES, BATTLES, NEW_BRAWLER, PARTNER_BATTLES,
    /** Сумма уровней силы всех бойцов (каждое улучшение силы = +1). */
    BRAWLER_POWER,
    /** Сумма рангов всех бойцов. */
    BRAWLER_RANK,
    /** Число открытых улучшений бойцов: гаджеты + звёздные силы + снаряжение + гиперзаряды. */
    UNLOCKS,
    /** Уровень аккаунта (expLevel). */
    EXP_LEVEL;

    /** Прогресс = число из профиля минус база при взятии квеста (а не журнал боёв). */
    public boolean usesProfileBaseline() {
        return this == TROPHIES || isProgression();
    }

    /** Квесты на «прокачку» — прогресс не про бои, а про развитие аккаунта. */
    public boolean isProgression() {
        return this == BRAWLER_POWER || this == BRAWLER_RANK || this == UNLOCKS || this == EXP_LEVEL;
    }
}
