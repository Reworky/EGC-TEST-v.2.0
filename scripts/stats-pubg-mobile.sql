-- PUBG Mobile: сколько игроков и какая активность (в сравнении с остальными играми). Только чтение.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-pubg-mobile.sql

-- 1) По всем играм за 30 дней: сколько игроков взяли квест, сколько заявок, сколько одобрено, процент, выплачено EXC.
SELECT q.game_name,
       COUNT(DISTINCT s.user_id) AS players_30d,
       COUNT(*) AS taken,
       SUM(CASE WHEN s.status = 'APPROVED' THEN 1 ELSE 0 END) AS approved,
       ROUND(100.0 * SUM(CASE WHEN s.status = 'APPROVED' THEN 1 ELSE 0 END) / COUNT(*), 1) AS approved_pct,
       SUM(CASE WHEN s.status = 'APPROVED' THEN COALESCE(s.awarded_coins, 0) ELSE 0 END) AS exc_paid
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
WHERE s.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)
GROUP BY q.game_name
ORDER BY players_30d DESC;

-- 2) PUBG Mobile: игроки за всё время и за 7 / 30 / 90 дней.
SELECT COUNT(DISTINCT s.user_id) AS players_all_time,
       COUNT(DISTINCT CASE WHEN s.created_at >= DATEADD('DAY', -7, CURRENT_TIMESTAMP) THEN s.user_id END) AS players_7d,
       COUNT(DISTINCT CASE WHEN s.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) THEN s.user_id END) AS players_30d,
       COUNT(DISTINCT CASE WHEN s.created_at >= DATEADD('DAY', -90, CURRENT_TIMESTAMP) THEN s.user_id END) AS players_90d
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
WHERE q.game_name = 'PUBG Mobile';

-- 3) PUBG Mobile по неделям (12 недель): игроки, заявки, одобрено.
SELECT FORMATDATETIME(s.created_at, 'yyyy-ww') AS week,
       COUNT(DISTINCT s.user_id) AS players,
       COUNT(*) AS taken,
       SUM(CASE WHEN s.status = 'APPROVED' THEN 1 ELSE 0 END) AS approved
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
WHERE q.game_name = 'PUBG Mobile' AND s.created_at >= DATEADD('DAY', -84, CURRENT_TIMESTAMP)
GROUP BY FORMATDATETIME(s.created_at, 'yyyy-ww')
ORDER BY week;

-- 4) PUBG Mobile за 30 дней: что решил ИИ-проверка скриншотов и чем закончилось (нагрузка на модераторов).
SELECT COALESCE(s.ai_decision, 'не проверялось') AS ai_decision, s.status, COUNT(*) AS cnt
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
WHERE q.game_name = 'PUBG Mobile' AND s.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)
GROUP BY COALESCE(s.ai_decision, 'не проверялось'), s.status
ORDER BY cnt DESC;

-- 5) PUBG Mobile: квесты по популярности за 30 дней (взято, одобрено, награда).
SELECT q.title, q.reward_coins, q.active,
       COUNT(*) AS taken,
       SUM(CASE WHEN s.status = 'APPROVED' THEN 1 ELSE 0 END) AS approved
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
WHERE q.game_name = 'PUBG Mobile' AND s.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)
GROUP BY q.id, q.title, q.reward_coins, q.active
ORDER BY taken DESC;
