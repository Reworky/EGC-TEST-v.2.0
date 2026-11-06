-- Страны игроков: сколько зарегистрировано, активно за 30 дней, 18+, выводило хоть раз. Только читает базу.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-countries.sql
SELECT COALESCE(NULLIF(u.country, ''), 'не указана') AS country,
  COUNT(*) AS registered,
  SUM(CASE WHEN GREATEST(COALESCE(u.last_activity_date, DATE '2000-01-01'),
                         CAST(COALESCE(u.last_bot_activity_at, TIMESTAMP '2000-01-01 00:00:00') AS DATE),
                         CAST(COALESCE(u.last_mini_app_open_at, TIMESTAMP '2000-01-01 00:00:00') AS DATE)) >= DATEADD('DAY', -30, CURRENT_DATE) THEN 1 ELSE 0 END) AS active_30d,
  SUM(CASE WHEN u.age >= 18 THEN 1 ELSE 0 END) AS age_18_plus,
  SUM(CASE WHEN u.age IS NOT NULL AND u.age < 18 THEN 1 ELSE 0 END) AS age_under_18,
  SUM(CASE WHEN EXISTS (SELECT 1 FROM reward_requests r JOIN reward_items ri ON ri.id = r.reward_id
                        WHERE r.user_id = u.id AND ri.category = 'Вывод' AND r.status = 'APPROVED') THEN 1 ELSE 0 END) AS ever_withdrew
FROM app_users u
WHERE u.registration_completed = TRUE AND COALESCE(u.blocked, FALSE) = FALSE
GROUP BY COALESCE(NULLIF(u.country, ''), 'не указана')
ORDER BY registered DESC;
