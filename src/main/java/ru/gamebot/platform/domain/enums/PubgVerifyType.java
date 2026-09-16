package ru.gamebot.platform.domain.enums;

/** WINS/MATCHES_PLAYED — без порога, каждый новый матч (или только победа) засчитывается как есть.
 *  TOP_N/KILLS/DAMAGE — используют Quest.pubgThreshold как условие ОДНОГО матча (winPlace<=threshold
 *  для TOP_N, kills>=threshold для KILLS, damageDealt>=threshold для DAMAGE); Quest.pubgTargetCount —
 *  по-прежнему сколько ТАКИХ матчей нужно накопить (1 — «за один матч», 3 — «трижды за неделю»). */
public enum PubgVerifyType { WINS, MATCHES_PLAYED, TOP_N, KILLS, DAMAGE }
