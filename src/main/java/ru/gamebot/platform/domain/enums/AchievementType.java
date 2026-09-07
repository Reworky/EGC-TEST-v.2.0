package ru.gamebot.platform.domain.enums;

/** Триггеры карточки-достижения (см. AchievementEvent) — победа в турнире, новый XP-уровень
 *  ("ранг") или круглая сумма накопленного EXC. Текстовая версия — генерация изображения
 *  сознательно не реализована, добавляется отдельно позже. */
public enum AchievementType { TOURNAMENT_WIN, RANK_UP, EXC_MILESTONE, INVITED_FRIENDS_MILESTONE }
