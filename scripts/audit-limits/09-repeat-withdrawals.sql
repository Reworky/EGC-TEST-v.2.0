-- 9) Возвращаются ли игроки за повторным выводом: сколько получили 1, 2, 3+ выплат и медиана дней между выплатами.
WITH p AS (SELECT rr.user_id, COALESCE(rr.paid_at, rr.created_at) AS at
           FROM reward_requests rr JOIN reward_items ri ON ri.id = rr.reward_id WHERE ri.category = 'Вывод' AND rr.status = 'APPROVED'),
c AS (SELECT user_id, COUNT(*) AS n FROM p GROUP BY user_id),
g AS (SELECT user_id, DATEDIFF('HOUR', LAG(at) OVER (PARTITION BY user_id ORDER BY at), at) / 24.0 AS gap_days FROM p)
SELECT (SELECT COUNT(*) FROM c) AS players_paid,
       (SELECT COUNT(*) FROM c WHERE n = 1) AS got_1,
       (SELECT COUNT(*) FROM c WHERE n = 2) AS got_2,
       (SELECT COUNT(*) FROM c WHERE n >= 3) AS got_3plus,
       (SELECT ROUND(MEDIAN(gap_days), 1) FROM g WHERE gap_days IS NOT NULL) AS median_days_between_payouts;
