-- Возвращает последнюю оплату «токены + рубли» игрока 5696809016 в открытые: статус PENDING, срок 24 часа считается заново с этого момента.
-- Сначала посмотрите scripts/check-ruble-order-5696809016.sql: если оплату отклонил сам модератор, возвращать её не нужно.
-- Запуск: cd /root/gamebot && bash scripts/run-sql.sh scripts/reopen-ruble-order-5696809016.sql
UPDATE shop_ruble_orders SET status = 'PENDING', decided_at = NULL, created_at = CURRENT_TIMESTAMP
WHERE id = (SELECT MAX(id) FROM shop_ruble_orders WHERE telegram_id = 5696809016 AND status IN ('EXPIRED', 'REJECTED'))
  AND NOT EXISTS (SELECT 1 FROM shop_ruble_orders WHERE telegram_id = 5696809016 AND status = 'PENDING');
SELECT id, telegram_id, game_key, package_key, exc_amount, rub_amount, status, created_at FROM shop_ruble_orders WHERE telegram_id = 5696809016 ORDER BY created_at DESC;
