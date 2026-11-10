package ru.gamebot.platform.domain.enums;

/** KILLS/WINS/MVPS/HEADSHOTS/BOMBS_PLANTED/BOMBS_DEFUSED/MATCHES_PLAYED — накопительная дельта
 *  кумулятивного career-счётчика с момента взятия квеста (как Clash Royale).
 *  LAST_MATCH_* — другой механизм: условие проверяется по статистике ПОСЛЕДНЕГО сыгранного матча
 *  (Steam отдаёт last_match_* отдельно от career-счётчиков), засчитывается первый же новый матч
 *  после взятия квеста, подходящий под условие (как Dota 2 "один подходящий матч", но без полной
 *  истории — виден только самый свежий матч на момент опроса). LAST_MATCH_DEATHS_MAX — "не более",
 *  остальные LAST_MATCH_* — "не менее". LAST_MATCH_KD_RATIO — цель хранится как отношение×100
 *  (200 = K/D 2.0).
 *  STAT_DELTA (10.11.2026) - дельта СУММЫ произвольных career-счётчиков Steam (имена в Quest.cs2StatKeys через запятую: оружие, карты, урон и т.д.) с момента взятия квеста.
 *  LAST_MATCH_DAMAGE - «не менее» по last_match_damage (урон за последний матч). */
public enum Cs2VerifyType {
    KILLS, WINS, MVPS, HEADSHOTS, BOMBS_PLANTED, BOMBS_DEFUSED, MATCHES_PLAYED,
    LAST_MATCH_KILLS, LAST_MATCH_DEATHS_MAX, LAST_MATCH_MVPS, LAST_MATCH_SCORE, LAST_MATCH_KD_RATIO,
    STAT_DELTA, LAST_MATCH_DAMAGE
}
