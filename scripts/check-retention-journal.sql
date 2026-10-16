-- Проверка журнала активных дней (вкладка «Удержание»). Только чтение.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-retention-journal.sql
-- Как читать: 1) строк по дням должно быть примерно столько же, сколько активных игроков в сутки (DAU); если 0 или единицы - журнал не пишется;
-- 2) «bot» - первая за день активность в боте, «app» - вход в мини-апп; 3) зарегистрированные после первого дня журнала - это и есть когорта вкладки.

-- 0) Какие из новых таблиц реально существуют (если строки нет - таблица не создалась):
SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME IN ('USER_ACTIVITY_DAYS', 'TRAFFIC_SPEND_ENTRIES', 'AD_PLACEMENT_STATS', 'BOT_AD_STATS', 'BOT_AD_STATS_DAILY');

SELECT MIN(activity_day) AS first_day, MAX(activity_day) AS last_day, COUNT(*) AS total_rows, COUNT(DISTINCT user_id) AS distinct_users FROM user_activity_days;

SELECT activity_day, source, COUNT(*) AS rows_count FROM user_activity_days GROUP BY activity_day, source ORDER BY activity_day, source;

SELECT CAST(u.created_at AS DATE) AS reg_day, COUNT(*) AS registered
FROM app_users u
WHERE u.registration_completed = TRUE AND CAST(u.created_at AS DATE) >= (SELECT MIN(activity_day) FROM user_activity_days)
GROUP BY CAST(u.created_at AS DATE) ORDER BY reg_day;
