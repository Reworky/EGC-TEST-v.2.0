-- Пилот «квесты без стен» (без кулдауна, награда по кривой убывания): кто и как часто его берёт. Только чтение.
-- Нужен, чтобы оценить спрос на «игру без лимитов» до любых планов продавать её за Stars / пробный период.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-pilot-nolimit.sql

-- 1) Какие квесты сейчас помечены как «без кулдауна» (должен быть один: Brawl Stars, потолок 4500 EXC/сутки).
SELECT q.id, q.title, q.game_name, q.active, q.reward_coins AS base_exc, q.target_period_ceiling AS ceiling_exc
FROM quests q
WHERE q.repeatable_no_cooldown_eligible = TRUE;

-- 2) По дням с 15.09.2026: сколько игроков, сколько засчитанных прохождений, сколько выплачено EXC.
SELECT CAST(s.updated_at AS DATE) AS day,
       COUNT(DISTINCT s.user_id) AS players,
       COUNT(*) AS approved,
       SUM(COALESCE(s.awarded_coins, 0)) AS exc_paid
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
WHERE q.repeatable_no_cooldown_eligible = TRUE AND s.status = 'APPROVED' AND s.updated_at >= DATE '2026-09-15'
GROUP BY CAST(s.updated_at AS DATE)
ORDER BY day;

-- 3) Главный вопрос: сколько игроков хоть раз прошли квест 2+ раз за сутки (то есть реально пользуются «без лимита»).
--    Распределение по максимуму прохождений за один день: 1 / 2 / 3 / 4+.
SELECT CASE WHEN mx >= 4 THEN '4+' ELSE CAST(mx AS VARCHAR) END AS max_per_day,
       COUNT(*) AS players
FROM (
    SELECT t.user_id, MAX(t.cnt) AS mx
    FROM (
        SELECT s.user_id, CAST(s.updated_at AS DATE) AS d, COUNT(*) AS cnt
        FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
        WHERE q.repeatable_no_cooldown_eligible = TRUE AND s.status = 'APPROVED' AND s.updated_at >= DATE '2026-09-15'
        GROUP BY s.user_id, CAST(s.updated_at AS DATE)
    ) t
    GROUP BY t.user_id
) m
GROUP BY CASE WHEN mx >= 4 THEN '4+' ELSE CAST(mx AS VARCHAR) END
ORDER BY max_per_day;

-- 4) Топ-15 по числу прохождений: сколько всего, в скольких днях, максимум за день, выплачено EXC.
SELECT u.id AS user_id, u.nickname,
       COUNT(*) AS approved_total,
       COUNT(DISTINCT CAST(s.updated_at AS DATE)) AS active_days,
       SUM(COALESCE(s.awarded_coins, 0)) AS exc_paid
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id JOIN app_users u ON u.id = s.user_id
WHERE q.repeatable_no_cooldown_eligible = TRUE AND s.status = 'APPROVED' AND s.updated_at >= DATE '2026-09-15'
GROUP BY u.id, u.nickname
ORDER BY approved_total DESC
LIMIT 15;

-- 5) Работает ли кривая убывания: средняя награда за 1-е, 2-е, 3-е... прохождение в один день.
SELECT n AS nth_in_day, COUNT(*) AS cnt, ROUND(AVG(coins), 0) AS avg_exc
FROM (
    SELECT COALESCE(s.awarded_coins, 0) AS coins,
           ROW_NUMBER() OVER (PARTITION BY s.user_id, CAST(s.updated_at AS DATE) ORDER BY s.updated_at) AS n
    FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
    WHERE q.repeatable_no_cooldown_eligible = TRUE AND s.status = 'APPROVED' AND s.updated_at >= DATE '2026-09-15'
) x
GROUP BY n
ORDER BY n;

-- 6) Доля от всех активных: сколько игроков пилота среди всех, кто брал любой квест за 30 дней.
SELECT (SELECT COUNT(DISTINCT s.user_id) FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
        WHERE q.repeatable_no_cooldown_eligible = TRUE AND s.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)) AS pilot_players_30d,
       (SELECT COUNT(DISTINCT s.user_id) FROM quest_submissions s
        WHERE s.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)) AS all_active_players_30d;
