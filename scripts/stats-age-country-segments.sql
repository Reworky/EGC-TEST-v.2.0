-- Сегменты потока для монетизации вывода: возраст x страна среди активных за 30 дней и среди выводивших. Только читает базу.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-age-country-segments.sql
CREATE LOCAL TEMPORARY TABLE seg AS
SELECT u.id,
  CASE WHEN u.age IS NULL THEN '0. возраст не указан'
       WHEN u.age < 14 THEN '1. до 14'
       WHEN u.age < 18 THEN '2. 14-17'
       ELSE '3. 18+' END AS age_group,
  CASE WHEN u.country IS NULL OR u.country = '' THEN 'не указана'
       WHEN LOWER(u.country) IN ('россия', 'russia', 'рф', 'ru') THEN 'Россия'
       ELSE 'другая страна' END AS country_group,
  GREATEST(COALESCE(u.last_activity_date, DATE '2000-01-01'),
           CAST(COALESCE(u.last_bot_activity_at, TIMESTAMP '2000-01-01 00:00:00') AS DATE),
           CAST(COALESCE(u.last_mini_app_open_at, TIMESTAMP '2000-01-01 00:00:00') AS DATE)) AS last_act,
  CASE WHEN EXISTS (SELECT 1 FROM reward_requests r JOIN reward_items ri ON ri.id = r.reward_id
                    WHERE r.user_id = u.id AND ri.category = 'Вывод' AND r.status = 'APPROVED') THEN 1 ELSE 0 END AS withdrew
FROM app_users u WHERE u.registration_completed = TRUE AND COALESCE(u.blocked, FALSE) = FALSE;

SELECT age_group, country_group,
  SUM(CASE WHEN last_act >= DATEADD('DAY', -30, CURRENT_DATE) THEN 1 ELSE 0 END) AS active_30d,
  SUM(withdrew) AS ever_withdrew,
  COUNT(*) AS registered
FROM seg GROUP BY age_group, country_group ORDER BY age_group, country_group;
