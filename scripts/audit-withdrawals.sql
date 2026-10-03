-- Аудит выводов (только чтение). Запуск: bash scripts/run-sql.sh scripts/audit-withdrawals.sql
-- 1) Больше одного вывода за 24 часа у одного игрока (кроме отменённых и отклонённых)
SELECT a.user_id, a.id AS req1, b.id AS req2, a.created_at AS t1, b.created_at AS t2
FROM reward_requests a
JOIN reward_requests b ON a.user_id = b.user_id AND b.id > a.id AND b.created_at < DATEADD('HOUR', 24, a.created_at)
JOIN reward_items ia ON ia.id = a.reward_id
JOIN reward_items ib ON ib.id = b.reward_id
WHERE ia.category = 'Вывод' AND ib.category = 'Вывод'
  AND a.status NOT IN ('CANCELLED', 'REJECTED') AND b.status NOT IN ('CANCELLED', 'REJECTED')
ORDER BY a.created_at DESC LIMIT 40;

-- 2) Виртуальные позиции вывода, использованные больше одного раза (не должно быть ни одной строки)
SELECT ri.id, ri.title, COUNT(rr.id) AS reqs, COUNT(DISTINCT rr.user_id) AS users
FROM reward_items ri
JOIN reward_requests rr ON rr.reward_id = ri.id
WHERE ri.category = 'Вывод' AND ri.active = FALSE
GROUP BY ri.id, ri.title
HAVING COUNT(rr.id) > 1
ORDER BY reqs DESC LIMIT 30;

-- 3) Выплаченные выводы, по которым в то же время был и возврат EXC (возможная двойная выплата)
SELECT rr.id, rr.user_id, ri.title, rr.paid_at, t.created_at AS refund_at, t.amount
FROM reward_requests rr
JOIN reward_items ri ON ri.id = rr.reward_id
JOIN exc_transactions t ON t.user_id = rr.user_id AND t.type = 'SHOP_REFUND'
  AND t.description LIKE ('%' || ri.title || '%')
  AND t.created_at >= rr.created_at AND t.created_at <= DATEADD('MINUTE', 5, rr.paid_at)
WHERE ri.category = 'Вывод' AND rr.status = 'APPROVED'
ORDER BY rr.paid_at DESC LIMIT 30;

-- 4) Потрачено в этом месяце больше месячного лимита уровня (возможны ложные срабатывания, если XP вырос после покупки)
SELECT u.id, u.telegram_id, u.xp, SUM(rr.paid_price_coins) AS spent
FROM reward_requests rr
JOIN reward_items ri ON ri.id = rr.reward_id
JOIN app_users u ON u.id = rr.user_id
WHERE rr.status NOT IN ('CANCELLED', 'REJECTED')
  AND COALESCE(ri.purchase_group, '') <> 'avatar_frame'
  AND rr.created_at >= DATE_TRUNC('MONTH', CURRENT_TIMESTAMP)
GROUP BY u.id, u.telegram_id, u.xp
HAVING SUM(rr.paid_price_coins) > CASE WHEN u.xp >= 75000 THEN 150000 WHEN u.xp >= 35000 THEN 100000
  WHEN u.xp >= 15000 THEN 80000 WHEN u.xp >= 5000 THEN 50000 WHEN u.xp >= 1000 THEN 25000 ELSE 10000 END
ORDER BY spent DESC LIMIT 30;

-- 5) Одни и те же реквизиты у разных игроков (возможные мультиаккаунты)
SELECT REGEXP_REPLACE(rr.payout_details, ':rubles=.*$', '') AS destination,
       COUNT(DISTINCT rr.user_id) AS users, COUNT(*) AS reqs
FROM reward_requests rr
JOIN reward_items ri ON ri.id = rr.reward_id
WHERE ri.category = 'Вывод' AND rr.payout_details IS NOT NULL AND rr.status <> 'REJECTED'
GROUP BY REGEXP_REPLACE(rr.payout_details, ':rubles=.*$', '')
HAVING COUNT(DISTINCT rr.user_id) > 1
ORDER BY users DESC LIMIT 30;
