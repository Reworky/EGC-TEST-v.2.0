-- PUBG Mobile: сколько реально одобрено и выплачено по каждому квесту, кто больше всех собирает, какая доля заявок отклоняется. Только чтение.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-pubg-mobile-rewards.sql
-- 1) квесты: награда, срок, активен ли
SELECT q.title, q.reward_coins, q.reward_xp, q.duration_days, q.active FROM quests q WHERE q.game_name = 'PUBG Mobile' ORDER BY q.active DESC, q.reward_coins, q.title;
-- 2) по каждому квесту: заявок, одобрено, отклонено, доля одобрений, сколько EXC выплачено, сколько разных игроков
SELECT q.title, q.reward_coins,
       COUNT(*) AS total,
       SUM(CASE WHEN s.status = 'APPROVED' THEN 1 ELSE 0 END) AS approved,
       SUM(CASE WHEN s.status = 'REJECTED' THEN 1 ELSE 0 END) AS rejected,
       ROUND(100.0 * SUM(CASE WHEN s.status = 'APPROVED' THEN 1 ELSE 0 END) / NULLIF(SUM(CASE WHEN s.status IN ('APPROVED','REJECTED') THEN 1 ELSE 0 END), 0), 0) AS approve_pct,
       SUM(CASE WHEN s.status = 'APPROVED' THEN q.reward_coins ELSE 0 END) AS paid_exc,
       COUNT(DISTINCT s.user_id) AS players
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
WHERE q.game_name = 'PUBG Mobile'
GROUP BY q.title, q.reward_coins ORDER BY paid_exc DESC;
-- 3) итого по игре и за последние 14 дней
SELECT COUNT(*) AS approved_total, SUM(q.reward_coins) AS paid_exc_total, COUNT(DISTINCT s.user_id) AS players,
       SUM(CASE WHEN s.updated_at >= DATEADD('DAY', -14, CURRENT_TIMESTAMP) THEN 1 ELSE 0 END) AS approved_14d,
       SUM(CASE WHEN s.updated_at >= DATEADD('DAY', -14, CURRENT_TIMESTAMP) THEN q.reward_coins ELSE 0 END) AS paid_exc_14d
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
WHERE q.game_name = 'PUBG Mobile' AND s.status = 'APPROVED';
-- 4) топ-15 игроков по одобренным квестам PUBG Mobile (подозрение на фарм: много одобрений за короткое время)
SELECT u.telegram_id, u.nickname, u.telegram_username, COUNT(*) AS approved, SUM(q.reward_coins) AS paid_exc,
       MIN(s.updated_at) AS first_at, MAX(s.updated_at) AS last_at
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id JOIN app_users u ON u.id = s.user_id
WHERE q.game_name = 'PUBG Mobile' AND s.status = 'APPROVED'
GROUP BY u.telegram_id, u.nickname, u.telegram_username ORDER BY approved DESC LIMIT 15;
-- 5) для сравнения: одобрений и выплат по всем играм за 14 дней
SELECT q.game_name, COUNT(*) AS approved_14d, SUM(q.reward_coins) AS paid_exc_14d, COUNT(DISTINCT s.user_id) AS players
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
WHERE s.status = 'APPROVED' AND s.updated_at >= DATEADD('DAY', -14, CURRENT_TIMESTAMP)
GROUP BY q.game_name ORDER BY paid_exc_14d DESC;
