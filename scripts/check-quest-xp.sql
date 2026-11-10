-- Опыт (XP) за квесты: у каких игр сколько XP за одобренный квест и сколько EXC на 1 XP; PUBG Mobile рядом с остальными. Только чтение.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-quest-xp.sql
-- 1) активные квесты по играм: сколько их, XP от/до, EXC от/до
SELECT q.game_name, COUNT(*) AS quests, MIN(q.reward_xp) AS xp_min, MAX(q.reward_xp) AS xp_max, MIN(q.reward_coins) AS exc_min, MAX(q.reward_coins) AS exc_max
FROM quests q WHERE q.active = TRUE GROUP BY q.game_name ORDER BY q.game_name;
-- 2) за 14 дней по играм: одобрений, средний XP и EXC за квест, всего XP, XP на игрока
SELECT q.game_name, COUNT(*) AS approved_14d, ROUND(AVG(q.reward_xp), 0) AS avg_xp, ROUND(AVG(q.reward_coins), 0) AS avg_exc,
       SUM(q.reward_xp) AS xp_total, COUNT(DISTINCT s.user_id) AS players, ROUND(SUM(q.reward_xp) * 1.0 / COUNT(DISTINCT s.user_id), 0) AS xp_per_player
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
WHERE s.status = 'APPROVED' AND s.updated_at >= DATEADD('DAY', -14, CURRENT_TIMESTAMP)
GROUP BY q.game_name ORDER BY xp_total DESC;
-- 3) PUBG Mobile: топ игроков по XP с квестов (за всё время) и их уровень сейчас
SELECT u.telegram_id, u.nickname, u.xp AS xp_now, SUM(q.reward_xp) AS xp_from_pubg_mobile, COUNT(*) AS approved
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id JOIN app_users u ON u.id = s.user_id
WHERE q.game_name = 'PUBG Mobile' AND s.status = 'APPROVED'
GROUP BY u.telegram_id, u.nickname, u.xp ORDER BY xp_from_pubg_mobile DESC LIMIT 10;
