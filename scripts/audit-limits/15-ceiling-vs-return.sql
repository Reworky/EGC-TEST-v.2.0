-- 15) «Упор в лимит -> уход?»: игроки с выплатой 14-60 дней назад. Делим по тому, сколько они вывели за календарный месяц выплаты
--     относительно своего лимита (в потолок / половина / мало) и смотрим, вернулись ли они хотя бы через 7 дней после выплаты и активны ли сейчас.
--     Если «в потолок» возвращаются и остаются не хуже остальных - лимит их не отпугивает, повышать его ради удержания нет оснований.
--     Оговорка: лимит берётся по текущему XP (XP только растёт, так что для старых выплат лимит мог быть ниже - доля «в потолок» слегка занижена).
WITH u AS (SELECT id,
                  CASE WHEN xp >= 75000 THEN 150000 WHEN xp >= 35000 THEN 100000 WHEN xp >= 15000 THEN 80000
                       WHEN xp >= 5000 THEN 50000 WHEN xp >= 1000 THEN 25000 ELSE 10000 END AS lim,
                  CASE WHEN last_bot_activity_at IS NOT NULL AND (last_activity_date IS NULL OR CAST(last_bot_activity_at AS DATE) > last_activity_date)
                       THEN CAST(last_bot_activity_at AS DATE) ELSE COALESCE(last_activity_date, CAST(created_at AS DATE)) END AS last_day
           FROM app_users WHERE registration_completed = TRUE AND blocked = FALSE),
pay AS (SELECT rr.user_id, COALESCE(rr.paid_price_coins, ri.price_coins) AS exc, CAST(COALESCE(rr.paid_at, rr.created_at) AS DATE) AS d
        FROM reward_requests rr JOIN reward_items ri ON ri.id = rr.reward_id
        WHERE ri.category = 'Вывод' AND rr.status = 'APPROVED'),
month_pay AS (SELECT user_id, YEAR(d) AS pay_year, MONTH(d) AS pay_month, SUM(exc) AS exc_month, MAX(d) AS last_pay
              FROM pay GROUP BY user_id, YEAR(d), MONTH(d))
SELECT CASE WHEN mp.exc_month >= u.lim * 0.9 THEN '1. в потолок (>= 90% лимита)'
            WHEN mp.exc_month >= u.lim * 0.5 THEN '2. 50-89% лимита'
            ELSE '3. меньше 50% лимита' END AS payout_vs_limit,
       COUNT(*) AS player_months,
       COUNT(DISTINCT mp.user_id) AS players,
       SUM(CASE WHEN u.last_day >= DATEADD('DAY', 7, mp.last_pay) THEN 1 ELSE 0 END) AS came_back_7d_after_payout,
       ROUND(100.0 * SUM(CASE WHEN u.last_day >= DATEADD('DAY', 7, mp.last_pay) THEN 1 ELSE 0 END) / COUNT(*), 1) AS pct_came_back,
       ROUND(100.0 * SUM(CASE WHEN u.last_day >= DATEADD('DAY', -7, CURRENT_DATE) THEN 1 ELSE 0 END) / COUNT(*), 1) AS pct_active_now
FROM month_pay mp JOIN u ON u.id = mp.user_id
WHERE mp.last_pay BETWEEN DATEADD('DAY', -60, CURRENT_DATE) AND DATEADD('DAY', -14, CURRENT_DATE)
GROUP BY payout_vs_limit
ORDER BY payout_vs_limit;
