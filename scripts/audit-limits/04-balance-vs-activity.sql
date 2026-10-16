-- 4) Активность по размеру баланса: удерживаются ли игроки с большими балансами лучше или хуже (последний заход: бот или мини-апп).
WITH u AS (SELECT coins,
                   CASE WHEN last_bot_activity_at IS NOT NULL AND (last_activity_date IS NULL OR CAST(last_bot_activity_at AS DATE) > last_activity_date)
                        THEN CAST(last_bot_activity_at AS DATE) ELSE COALESCE(last_activity_date, CAST(created_at AS DATE)) END AS last_day
           FROM app_users WHERE registration_completed = TRUE AND blocked = FALSE)
SELECT CASE WHEN coins < 1000 THEN '1. до 1 000' WHEN coins < 3000 THEN '2. 1 000-2 999' WHEN coins < 5000 THEN '3. 3 000-4 999 (в шаге от вывода)'
            WHEN coins < 10000 THEN '4. 5 000-9 999' WHEN coins < 25000 THEN '5. 10 000-24 999' WHEN coins < 50000 THEN '6. 25 000-49 999' ELSE '7. 50 000+' END AS balance_band,
       COUNT(*) AS players,
       SUM(CASE WHEN last_day >= DATEADD('DAY', -7, CURRENT_DATE) THEN 1 ELSE 0 END) AS active_7d,
       SUM(CASE WHEN last_day < DATEADD('DAY', -7, CURRENT_DATE) AND last_day >= DATEADD('DAY', -30, CURRENT_DATE) THEN 1 ELSE 0 END) AS lapsed_8_30d,
       SUM(CASE WHEN last_day < DATEADD('DAY', -30, CURRENT_DATE) THEN 1 ELSE 0 END) AS gone_30d_plus,
       ROUND(100.0 * SUM(CASE WHEN last_day >= DATEADD('DAY', -7, CURRENT_DATE) THEN 1 ELSE 0 END) / COUNT(*), 1) AS pct_active_7d
FROM u GROUP BY 1 ORDER BY 1;
