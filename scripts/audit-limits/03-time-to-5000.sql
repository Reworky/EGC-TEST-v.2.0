-- 3) За сколько дней игроки набирают первые 5 000 EXC (минимум вывода): медиана и процентили среди тех, кто набрал, и доля набравших
--    за 7 / 14 / 30 дней среди игроков, зарегистрированных не меньше 30 дней назад. Если 5 000 набирают единицы - порог недосягаем для большинства.
WITH cum AS (SELECT user_id, created_at, SUM(amount) OVER (PARTITION BY user_id ORDER BY created_at, id) AS running
             FROM exc_transactions WHERE amount > 0),
first5 AS (SELECT user_id, MIN(created_at) AS reached_at FROM cum WHERE running >= 5000 GROUP BY user_id),
base AS (SELECT u.id, u.created_at, f.reached_at FROM app_users u LEFT JOIN first5 f ON f.user_id = u.id
         WHERE u.registration_completed = TRUE AND u.blocked = FALSE AND u.created_at <= DATEADD('DAY', -30, CURRENT_TIMESTAMP))
SELECT COUNT(*) AS cohort_30d_plus,
       SUM(CASE WHEN reached_at IS NOT NULL THEN 1 ELSE 0 END) AS ever_reached_5000,
       ROUND(100.0 * SUM(CASE WHEN reached_at <= DATEADD('DAY', 7, created_at) THEN 1 ELSE 0 END) / COUNT(*), 1) AS pct_within_7d,
       ROUND(100.0 * SUM(CASE WHEN reached_at <= DATEADD('DAY', 14, created_at) THEN 1 ELSE 0 END) / COUNT(*), 1) AS pct_within_14d,
       ROUND(100.0 * SUM(CASE WHEN reached_at <= DATEADD('DAY', 30, created_at) THEN 1 ELSE 0 END) / COUNT(*), 1) AS pct_within_30d,
       ROUND(MEDIAN(CASE WHEN reached_at IS NOT NULL THEN DATEDIFF('HOUR', created_at, reached_at) / 24.0 END), 1) AS median_days_to_5000
FROM base;
