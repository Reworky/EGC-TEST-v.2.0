-- Первая сессия новичка по источникам трафика (за 45 дней), отдельно «до онбординга v2» (регистрация до 10.10) и «после» (с 10.10). Только чтение.
-- Показывает, какие источники дают игроков, которые берут квест, получают награду и доходят до 3 квестов (открытие вывода).
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-first-session-by-source.sql
SELECT COALESCE(NULLIF(u.traffic_source_code, ''), '(без метки)') AS source,
       CASE WHEN u.created_at < TIMESTAMP '2026-10-10 00:00:00' THEN '1. до v2' ELSE '2. после v2' END AS period,
       COUNT(*) AS new_players,
       SUM(CASE WHEN EXISTS (SELECT 1 FROM quest_submissions s WHERE s.user_id = u.id AND s.status <> 'DRAFT'
                              AND s.created_at < DATEADD('HOUR', 24, u.created_at)) THEN 1 ELSE 0 END) AS took_quest_24h,
       SUM(CASE WHEN EXISTS (SELECT 1 FROM quest_submissions s WHERE s.user_id = u.id AND s.status = 'APPROVED'
                              AND s.updated_at < DATEADD('HOUR', 24, u.created_at)) THEN 1 ELSE 0 END) AS got_reward_24h,
       SUM(CASE WHEN u.completed_quests >= 3 THEN 1 ELSE 0 END) AS reached_3_quests,
       ROUND(100.0 * SUM(CASE WHEN EXISTS (SELECT 1 FROM quest_submissions s WHERE s.user_id = u.id AND s.status <> 'DRAFT'
                              AND s.created_at < DATEADD('HOUR', 24, u.created_at)) THEN 1 ELSE 0 END) / COUNT(*), 1) AS pct_took_24h,
       ROUND(100.0 * SUM(CASE WHEN u.completed_quests >= 3 THEN 1 ELSE 0 END) / COUNT(*), 1) AS pct_3_quests
FROM app_users u
WHERE u.created_at >= DATEADD('DAY', -45, CURRENT_TIMESTAMP)
GROUP BY COALESCE(NULLIF(u.traffic_source_code, ''), '(без метки)'),
         CASE WHEN u.created_at < TIMESTAMP '2026-10-10 00:00:00' THEN '1. до v2' ELSE '2. после v2' END
HAVING COUNT(*) >= 10
ORDER BY period, new_players DESC;
