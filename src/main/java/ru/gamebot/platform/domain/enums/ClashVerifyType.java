package ru.gamebot.platform.domain.enums;

public enum ClashVerifyType { ATTACK_WINS, RESOURCES, TOWN_HALL, TROPHIES, WAR_STARS, DONATIONS, DEFENSE_WINS, EXP_LEVEL, BUILDER_TROPHIES,
    /** Ачивка по имени (Quest.clashAchievementName) — накопительная за всю историю, не сбрасывается по сезонам. */
    ACHIEVEMENT,
    /** Сумма уровней всех героев. */
    HERO_LEVELS,
    /** Сумма уровней войск и заклинаний. */
    TROOP_LEVELS,
    /** Уровень Зала строителя. */
    BUILDER_HALL }
