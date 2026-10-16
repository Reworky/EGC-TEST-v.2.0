-- 6) Месячный лимит вывода (по XP: 10k / 25k / 50k / 80k / 100k / 150k EXC): сколько игроков на каждой ступени, у скольких баланс больше лимита
--    и сколько уже выбрали >= 90% лимита в текущем месяце (если много - лимит давит).
WITH u AS (SELECT id, coins, monthly_withdrawn_exc AS used_now,
                   CASE WHEN xp >= 75000 THEN 150000 WHEN xp >= 35000 THEN 100000 WHEN xp >= 15000 THEN 80000
                        WHEN xp >= 5000 THEN 50000 WHEN xp >= 1000 THEN 25000 ELSE 10000 END AS lim,
                   CASE WHEN last_bot_activity_at IS NOT NULL AND (last_activity_date IS NULL OR CAST(last_bot_activity_at AS DATE) > last_activity_date)
                        THEN CAST(last_bot_activity_at AS DATE) ELSE COALESCE(last_activity_date, CAST(created_at AS DATE)) END AS last_day
           FROM app_users WHERE registration_completed = TRUE AND blocked = FALSE)
SELECT lim AS monthly_limit_exc, COUNT(*) AS players,
       SUM(CASE WHEN last_day >= DATEADD('DAY', -30, CURRENT_DATE) THEN 1 ELSE 0 END) AS active_30d,
       SUM(CASE WHEN coins > lim THEN 1 ELSE 0 END) AS balance_above_limit,
       SUM(CASE WHEN used_now >= lim * 0.9 THEN 1 ELSE 0 END) AS used_90pct_this_month,
       SUM(CASE WHEN last_day >= DATEADD('DAY', -30, CURRENT_DATE) AND coins > 0 THEN GREATEST(coins - lim, 0) ELSE 0 END) AS exc_above_limit_active
FROM u GROUP BY lim ORDER BY lim;
