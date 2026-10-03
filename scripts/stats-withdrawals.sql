-- Выводы игроков: по месяцам и в среднем (только чтение). Запуск: bash scripts/run-sql.sh scripts/stats-withdrawals.sql
-- Считаются только ВЫПЛАЧЕННЫЕ заявки (статус APPROVED), месяц - по дате выплаты. EXC - сколько списано у игроков,
-- RUB - сумма в рублях, записанная в заявке (у вывода в Stars её нет, там только EXC).

-- 1) По месяцам: сколько выплат, сколько разных игроков, сколько EXC и рублей
SELECT FORMATDATETIME(rr.paid_at, 'yyyy-MM') AS ym,
       COUNT(*) AS payouts,
       COUNT(DISTINCT rr.user_id) AS players,
       SUM(COALESCE(rr.paid_price_coins, ri.price_coins)) AS exc,
       SUM(COALESCE(rr.fixed_rub_value, 0)) AS rub
FROM reward_requests rr
JOIN reward_items ri ON ri.id = rr.reward_id
WHERE ri.category = 'Вывод' AND rr.status = 'APPROVED' AND rr.paid_at IS NOT NULL
GROUP BY FORMATDATETIME(rr.paid_at, 'yyyy-MM')
ORDER BY ym;

-- 2) По месяцам и способу вывода: рубли (СБП/карта), TON, Stars
SELECT FORMATDATETIME(rr.paid_at, 'yyyy-MM') AS ym,
       CASE WHEN ri.purchase_group = 'telegram_stars' THEN 'Stars'
            WHEN rr.payout_details LIKE 'TON:%' OR rr.payout_details LIKE 'USDT%' THEN 'TON'
            ELSE 'Рубли' END AS method,
       COUNT(*) AS payouts,
       SUM(COALESCE(rr.paid_price_coins, ri.price_coins)) AS exc,
       SUM(COALESCE(rr.fixed_rub_value, 0)) AS rub
FROM reward_requests rr
JOIN reward_items ri ON ri.id = rr.reward_id
WHERE ri.category = 'Вывод' AND rr.status = 'APPROVED' AND rr.paid_at IS NOT NULL
GROUP BY FORMATDATETIME(rr.paid_at, 'yyyy-MM'),
         CASE WHEN ri.purchase_group = 'telegram_stars' THEN 'Stars'
              WHEN rr.payout_details LIKE 'TON:%' OR rr.payout_details LIKE 'USDT%' THEN 'TON'
              ELSE 'Рубли' END
ORDER BY ym, method;

-- 3) Итог и среднее за 30 дней (от первой выплаты до сегодня)
SELECT COUNT(*) AS payouts_total,
       COUNT(DISTINCT rr.user_id) AS players_total,
       SUM(COALESCE(rr.paid_price_coins, ri.price_coins)) AS exc_total,
       SUM(COALESCE(rr.fixed_rub_value, 0)) AS rub_total,
       MIN(rr.paid_at) AS first_payout,
       DATEDIFF('DAY', MIN(rr.paid_at), CURRENT_TIMESTAMP) AS days_span,
       ROUND(SUM(COALESCE(rr.paid_price_coins, ri.price_coins)) * 30.0 / GREATEST(1, DATEDIFF('DAY', MIN(rr.paid_at), CURRENT_TIMESTAMP)), 0) AS exc_per_30_days,
       ROUND(SUM(COALESCE(rr.fixed_rub_value, 0)) * 30.0 / GREATEST(1, DATEDIFF('DAY', MIN(rr.paid_at), CURRENT_TIMESTAMP)), 0) AS rub_per_30_days,
       ROUND(COUNT(*) * 30.0 / GREATEST(1, DATEDIFF('DAY', MIN(rr.paid_at), CURRENT_TIMESTAMP)), 1) AS payouts_per_30_days
FROM reward_requests rr
JOIN reward_items ri ON ri.id = rr.reward_id
WHERE ri.category = 'Вывод' AND rr.status = 'APPROVED' AND rr.paid_at IS NOT NULL;

-- 4) Средняя сумма одной выплаты и сколько в среднем выводит один игрок за всё время
SELECT ROUND(AVG(COALESCE(rr.paid_price_coins, ri.price_coins)), 0) AS avg_exc_per_payout,
       ROUND(SUM(COALESCE(rr.paid_price_coins, ri.price_coins)) * 1.0 / COUNT(DISTINCT rr.user_id), 0) AS avg_exc_per_player,
       ROUND(SUM(COALESCE(rr.fixed_rub_value, 0)) * 1.0 / COUNT(DISTINCT rr.user_id), 0) AS avg_rub_per_player
FROM reward_requests rr
JOIN reward_items ri ON ri.id = rr.reward_id
WHERE ri.category = 'Вывод' AND rr.status = 'APPROVED' AND rr.paid_at IS NOT NULL;
