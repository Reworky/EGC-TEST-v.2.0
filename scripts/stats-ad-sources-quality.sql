-- Качество закупов по источникам трафика: из тех, кто перешёл в бота, сколько стали «полноценными игроками». Только чтение.
-- Переход = строка игрока с меткой источника (traffic_source_code). Лестница: прошёл онбординг → 1 одобренный квест → 3 одобренных (открыт вывод) → вернулся спустя 7+ дней.
-- «Вернулся 7+» считается только у тех, кто зарегистрировался больше 7 дней назад (колонка old7 = их число), иначе молодые когорты занижают процент.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-ad-sources-quality.sql
SELECT COALESCE(NULLIF(u.traffic_source_code, ''), '(без метки)') AS source,
       COUNT(*) AS joined,
       SUM(CASE WHEN u.registration_completed THEN 1 ELSE 0 END) AS onboarded,
       SUM(CASE WHEN u.completed_quests >= 1 THEN 1 ELSE 0 END) AS q1,
       SUM(CASE WHEN u.completed_quests >= 3 THEN 1 ELSE 0 END) AS q3,
       ROUND(100.0 * SUM(CASE WHEN u.completed_quests >= 1 THEN 1 ELSE 0 END) / COUNT(*), 1) AS pct_q1,
       ROUND(100.0 * SUM(CASE WHEN u.completed_quests >= 3 THEN 1 ELSE 0 END) / COUNT(*), 1) AS pct_q3,
       SUM(CASE WHEN u.created_at < DATEADD('DAY', -7, CURRENT_TIMESTAMP) THEN 1 ELSE 0 END) AS old7,
       SUM(CASE WHEN u.created_at < DATEADD('DAY', -7, CURRENT_TIMESTAMP)
                 AND EXISTS (SELECT 1 FROM user_activity_days a WHERE a.user_id = u.id
                             AND a.activity_day >= DATEADD('DAY', 7, CAST(DATEADD('HOUR', 3, u.created_at) AS DATE))) THEN 1 ELSE 0 END) AS back7
FROM app_users u
WHERE u.created_at >= DATEADD('DAY', -90, CURRENT_TIMESTAMP)
GROUP BY COALESCE(NULLIF(u.traffic_source_code, ''), '(без метки)')
HAVING COUNT(*) >= 15
ORDER BY joined DESC;
