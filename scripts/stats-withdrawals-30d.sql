-- Сколько реально выведено за последние 30 дней: EXC и рубли по способам и по неделям, число игроков. Только читает базу.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-withdrawals-30d.sql
-- Выплата = заявка на вывод (категория «Вывод») со статусом APPROVED; рубли = зафиксированная при заявке сумма, у звёзд и старых заявок может быть пусто (тогда только EXC).
CREATE LOCAL TEMPORARY TABLE w AS
SELECT r.id, r.user_id, COALESCE(r.paid_at, r.created_at) AS at, r.fixed_rub_value AS rub,
  COALESCE(r.paid_price_coins, i.price_coins) AS exc,
  CASE WHEN i.purchase_group = 'telegram_stars' THEN 'звёзды'
       WHEN r.payout_details LIKE 'TON%' OR r.payout_details LIKE 'USDT%' THEN 'крипта'
       ELSE 'рубли' END AS method
FROM reward_requests r JOIN reward_items i ON i.id = r.reward_id
WHERE i.category = 'Вывод' AND r.status = 'APPROVED' AND COALESCE(r.paid_at, r.created_at) >= DATEADD('DAY', -30, CURRENT_TIMESTAMP);

SELECT 'итого за 30 дней' AS part, COUNT(*) AS requests, COUNT(DISTINCT user_id) AS players, SUM(exc) AS exc, SUM(COALESCE(rub, 0)) AS rub FROM w;
SELECT method, COUNT(*) AS requests, COUNT(DISTINCT user_id) AS players, SUM(exc) AS exc, SUM(COALESCE(rub, 0)) AS rub FROM w GROUP BY method ORDER BY exc DESC;
SELECT FLOOR(DATEDIFF('DAY', at, CURRENT_TIMESTAMP) / 7) AS weeks_ago, COUNT(*) AS requests, SUM(exc) AS exc, SUM(COALESCE(rub, 0)) AS rub FROM w GROUP BY FLOOR(DATEDIFF('DAY', at, CURRENT_TIMESTAMP) / 7) ORDER BY weeks_ago;
