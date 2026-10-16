-- 8) Размер выплат: сколько EXC выводят за раз (много ли выплат «в обрез около минимума»).
SELECT payout_size, COUNT(*) AS payouts, COUNT(DISTINCT user_id) AS players
FROM (SELECT x.user_id,
             CASE WHEN x.exc < 5000 THEN '0. меньше 5 000' WHEN x.exc < 7000 THEN '1. 5 000-6 999 (около минимума)' WHEN x.exc < 10000 THEN '2. 7 000-9 999'
                  WHEN x.exc < 25000 THEN '3. 10 000-24 999' ELSE '4. 25 000+' END AS payout_size
      FROM (SELECT rr.user_id, COALESCE(rr.paid_price_coins, ri.price_coins) AS exc
            FROM reward_requests rr JOIN reward_items ri ON ri.id = rr.reward_id
            WHERE ri.category = 'Вывод' AND rr.status = 'APPROVED') x) t
GROUP BY payout_size ORDER BY payout_size;
