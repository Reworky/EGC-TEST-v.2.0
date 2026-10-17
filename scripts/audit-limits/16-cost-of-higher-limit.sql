-- 16) Сколько стоит поднять лимит ядру по числу квестов (10 квестов -> не меньше 25 000, 30 квестов -> не меньше 50 000 EXC/мес)
--     при курсе фонда 38% (поменять в первой строке, если фонд другой). Считается ВЕРХНЯЯ граница: игрок вывел бы всё, что заработал за 30 дней
--     сверх старого лимита, но не больше нового. Реально выведут не все - см. сценарии 25/50/100% во втором запросе.
--     Курс: 1 000 EXC = 10 ₽ при фонде 100%, т.е. рубли = EXC * фонд% / 10 000.
WITH hr AS (SELECT 38 AS pct),
earned AS (SELECT user_id, SUM(amount) AS earned30 FROM exc_transactions
           WHERE amount > 0 AND created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) GROUP BY user_id),
u AS (SELECT id, completed_quests,
             CASE WHEN xp >= 75000 THEN 150000 WHEN xp >= 35000 THEN 100000 WHEN xp >= 15000 THEN 80000
                  WHEN xp >= 5000 THEN 50000 WHEN xp >= 1000 THEN 25000 ELSE 10000 END AS lim_old
      FROM app_users WHERE registration_completed = TRUE AND blocked = FALSE),
x AS (SELECT u.id, u.completed_quests, e.earned30, u.lim_old,
             GREATEST(u.lim_old, CASE WHEN u.completed_quests >= 30 THEN 50000 WHEN u.completed_quests >= 10 THEN 25000 ELSE 0 END) AS lim_new
      FROM u JOIN earned e ON e.user_id = u.id)
SELECT CASE WHEN completed_quests >= 30 THEN '30+ квестов' WHEN completed_quests >= 10 THEN '10-29 квестов' ELSE 'меньше 10 квестов' END AS tier,
       COUNT(*) AS earners_30d,
       SUM(CASE WHEN LEAST(earned30, lim_new) > LEAST(earned30, lim_old) THEN 1 ELSE 0 END) AS gain_from_new_limit,
       SUM(LEAST(earned30, lim_new) - LEAST(earned30, lim_old)) AS extra_exc_upper_bound,
       ROUND(SUM(LEAST(earned30, lim_new) - LEAST(earned30, lim_old)) * (SELECT pct FROM hr) / 10000.0) AS extra_rub_upper_bound
FROM x GROUP BY tier ORDER BY tier;

WITH hr AS (SELECT 38 AS pct),
earned AS (SELECT user_id, SUM(amount) AS earned30 FROM exc_transactions
           WHERE amount > 0 AND created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) GROUP BY user_id),
u AS (SELECT id, completed_quests,
             CASE WHEN xp >= 75000 THEN 150000 WHEN xp >= 35000 THEN 100000 WHEN xp >= 15000 THEN 80000
                  WHEN xp >= 5000 THEN 50000 WHEN xp >= 1000 THEN 25000 ELSE 10000 END AS lim_old
      FROM app_users WHERE registration_completed = TRUE AND blocked = FALSE),
x AS (SELECT u.id, e.earned30, u.lim_old,
             GREATEST(u.lim_old, CASE WHEN u.completed_quests >= 30 THEN 50000 WHEN u.completed_quests >= 10 THEN 25000 ELSE 0 END) AS lim_new
      FROM u JOIN earned e ON e.user_id = u.id),
tot AS (SELECT SUM(LEAST(earned30, lim_new) - LEAST(earned30, lim_old)) AS extra_exc FROM x)
SELECT '100% доп. суммы выведут' AS scenario, extra_exc, ROUND(extra_exc * (SELECT pct FROM hr) / 10000.0) AS extra_rub_per_month FROM tot
UNION ALL SELECT '50% выведут', ROUND(extra_exc * 0.5), ROUND(extra_exc * 0.5 * (SELECT pct FROM hr) / 10000.0) FROM tot
UNION ALL SELECT '25% выведут', ROUND(extra_exc * 0.25), ROUND(extra_exc * 0.25 * (SELECT pct FROM hr) / 10000.0) FROM tot;
