-- Размеры рублёвых выводов (СБП) за последние 90 дней: нужно, чтобы выбрать порог, выше которого вывод только на карту партнёра. Только чтение.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-withdrawals-sizes.sql
-- Считаются выплаченные заявки (APPROVED); рубли - сумма в заявке (fixed_rub_value), EXC - сколько списано.

-- 1) Сколько выплат и игроков в каждом диапазоне рублей, и какая доля денег уходит выше порога.
SELECT CASE WHEN COALESCE(rr.fixed_rub_value, 0) < 300 THEN '1) до 300 ₽'
            WHEN rr.fixed_rub_value < 1000 THEN '2) 300-999 ₽'
            WHEN rr.fixed_rub_value < 3000 THEN '3) 1000-2999 ₽'
            WHEN rr.fixed_rub_value < 10000 THEN '4) 3000-9999 ₽'
            ELSE '5) от 10000 ₽' END AS bucket,
       COUNT(*) AS payouts,
       COUNT(DISTINCT rr.user_id) AS players,
       SUM(COALESCE(rr.fixed_rub_value, 0)) AS rub,
       SUM(COALESCE(rr.paid_price_coins, ri.price_coins)) AS exc
FROM reward_requests rr
JOIN reward_items ri ON ri.id = rr.reward_id
WHERE ri.category = 'Вывод' AND rr.status = 'APPROVED' AND rr.paid_at IS NOT NULL
  AND rr.paid_at >= DATEADD('DAY', -90, CURRENT_TIMESTAMP)
  AND COALESCE(ri.purchase_group, '') <> 'telegram_stars'
  AND NOT (rr.payout_details LIKE 'TON:%' OR rr.payout_details LIKE 'USDT%')
GROUP BY CASE WHEN COALESCE(rr.fixed_rub_value, 0) < 300 THEN '1) до 300 ₽'
              WHEN rr.fixed_rub_value < 1000 THEN '2) 300-999 ₽'
              WHEN rr.fixed_rub_value < 3000 THEN '3) 1000-2999 ₽'
              WHEN rr.fixed_rub_value < 10000 THEN '4) 3000-9999 ₽'
              ELSE '5) от 10000 ₽' END
ORDER BY bucket;

-- 2) Сколько рублей выводит один игрок суммарно за 90 дней: сколько таких игроков в каждом диапазоне.
SELECT CASE WHEN t.rub < 500 THEN '1) до 500 ₽'
            WHEN t.rub < 2000 THEN '2) 500-1999 ₽'
            WHEN t.rub < 5000 THEN '3) 2000-4999 ₽'
            ELSE '4) от 5000 ₽' END AS player_total,
       COUNT(*) AS players,
       SUM(t.rub) AS rub
FROM (
  SELECT rr.user_id, SUM(COALESCE(rr.fixed_rub_value, 0)) AS rub
  FROM reward_requests rr
  JOIN reward_items ri ON ri.id = rr.reward_id
  WHERE ri.category = 'Вывод' AND rr.status = 'APPROVED' AND rr.paid_at IS NOT NULL
    AND rr.paid_at >= DATEADD('DAY', -90, CURRENT_TIMESTAMP)
    AND COALESCE(ri.purchase_group, '') <> 'telegram_stars'
    AND NOT (rr.payout_details LIKE 'TON:%' OR rr.payout_details LIKE 'USDT%')
  GROUP BY rr.user_id
) t
GROUP BY CASE WHEN t.rub < 500 THEN '1) до 500 ₽'
              WHEN t.rub < 2000 THEN '2) 500-1999 ₽'
              WHEN t.rub < 5000 THEN '3) 2000-4999 ₽'
              ELSE '4) от 5000 ₽' END
ORDER BY player_total;
