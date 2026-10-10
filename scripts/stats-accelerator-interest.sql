-- Сколько человек нажали «✋ Хочу» на «Ускорителе (скоро)» в магазине (fake door). Только чтение.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-accelerator-interest.sql
SELECT COUNT(*) AS total_interested, MIN(created_at) AS first_click, MAX(created_at) AS last_click
FROM feature_interests WHERE feature_code = 'ACCELERATOR';

SELECT CAST(created_at AS DATE) AS dt, COUNT(*) AS clicks
FROM feature_interests WHERE feature_code = 'ACCELERATOR'
GROUP BY CAST(created_at AS DATE) ORDER BY dt DESC;

-- Сколько всего живых игроков смотрит магазин: для сравнения (доля нажавших от активных за 30 дней)
SELECT COUNT(*) AS active_players_30d FROM app_users WHERE last_bot_activity_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP);
