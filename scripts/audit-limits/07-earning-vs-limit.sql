-- 7) Сколько EXC игроки зарабатывают за 30 дней и упирается ли это в их месячный лимит вывода.
WITH e AS (SELECT user_id, SUM(amount) AS earned30 FROM exc_transactions WHERE amount > 0 AND created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) GROUP BY user_id),
u AS (SELECT id, CASE WHEN xp >= 75000 THEN 150000 WHEN xp >= 35000 THEN 100000 WHEN xp >= 15000 THEN 80000
                      WHEN xp >= 5000 THEN 50000 WHEN xp >= 1000 THEN 25000 ELSE 10000 END AS lim
      FROM app_users WHERE registration_completed = TRUE AND blocked = FALSE)
SELECT CASE WHEN e.earned30 < 2000 THEN '1. до 2 000' WHEN e.earned30 < 5000 THEN '2. 2 000-4 999' WHEN e.earned30 < 10000 THEN '3. 5 000-9 999'
            WHEN e.earned30 < 25000 THEN '4. 10 000-24 999' WHEN e.earned30 < 50000 THEN '5. 25 000-49 999' ELSE '6. 50 000+' END AS earned_30d_band,
       COUNT(*) AS players, ROUND(AVG(u.lim)) AS avg_monthly_limit,
       SUM(CASE WHEN e.earned30 > u.lim THEN 1 ELSE 0 END) AS earn_more_than_limit
FROM e JOIN u ON u.id = e.user_id GROUP BY 1 ORDER BY 1;
