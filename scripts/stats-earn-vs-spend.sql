-- Только чтение. «Зарабатывают быстро, тратят долго?» Игровой баланс EXC (без EXCASH), активные за 30 дней, без заблокированных.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-earn-vs-spend.sql
-- Лимит ниже - базовый по XP (без прибавки EGC Pass).

-- 1) Заработок за 30 дней в долях месячного лимита: сколько игроков и сколько EXC начислено сверх лимита.
--    Возвраты покупок (SHOP_REFUND) и переводы (TRANSFER) заработком не считаются.
SELECT band, COUNT(*) AS players, SUM(earned) AS exc_earned, SUM(GREATEST(earned - lim, 0)) AS exc_above_limit
FROM (SELECT e.earned, u.lim,
             CASE WHEN e.earned <= u.lim * 0.5 THEN '1. до половины лимита' WHEN e.earned <= u.lim THEN '2. 50-100%'
                  WHEN e.earned <= u.lim * 2 THEN '3. 1-2 лимита' WHEN e.earned <= u.lim * 4 THEN '4. 2-4 лимита' ELSE '5. больше 4 лимитов' END AS band
      FROM (SELECT user_id, SUM(amount) AS earned FROM exc_transactions
            WHERE amount > 0 AND sponsor = FALSE AND type NOT IN ('SHOP_REFUND', 'TRANSFER') AND created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)
            GROUP BY user_id) e
      JOIN (SELECT id, CASE WHEN xp >= 75000 THEN 150000 WHEN xp >= 35000 THEN 100000 WHEN xp >= 15000 THEN 80000
                            WHEN xp >= 5000 THEN 50000 WHEN xp >= 1000 THEN 25000 ELSE 10000 END AS lim
            FROM app_users WHERE blocked = FALSE) u ON u.id = e.user_id) t
GROUP BY band ORDER BY band;

-- 2) Накопленные балансы активных: за сколько месяцев их можно потратить при нынешнем лимите.
SELECT band, COUNT(*) AS players, SUM(coins) AS exc_on_balance
FROM (SELECT coins,
             CASE WHEN coins <= lim THEN '1. тратится за 1 месяц' WHEN coins <= lim * 3 THEN '2. 2-3 месяца'
                  WHEN coins <= lim * 12 THEN '3. 4-12 месяцев' ELSE '4. больше года' END AS band
      FROM (SELECT coins, CASE WHEN xp >= 75000 THEN 150000 WHEN xp >= 35000 THEN 100000 WHEN xp >= 15000 THEN 80000
                               WHEN xp >= 5000 THEN 50000 WHEN xp >= 1000 THEN 25000 ELSE 10000 END AS lim
            FROM app_users WHERE blocked = FALSE AND coins > 0 AND last_activity_date >= DATEADD('DAY', -30, CURRENT_DATE)) a) t
GROUP BY band ORDER BY band;

-- 3) Откуда берутся и куда уходят EXC за 30 дней.
SELECT type, SUM(CASE WHEN amount > 0 THEN amount ELSE 0 END) AS exc_in, SUM(CASE WHEN amount < 0 THEN -amount ELSE 0 END) AS exc_out,
       COUNT(DISTINCT user_id) AS players
FROM exc_transactions WHERE sponsor = FALSE AND created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)
GROUP BY type ORDER BY exc_in DESC, exc_out DESC;
