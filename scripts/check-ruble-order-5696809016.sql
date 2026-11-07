-- Куда делась оплата доната «токены + рубли» игрока 5696809016 (60 UC PUBG Mobile, 30 ₽ + 9 930 EXC). Только чтение.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-ruble-order-5696809016.sql
-- Статусы: PENDING - открыта (видна в «Выдача → Донат»), EXPIRED - закрылась через 24 часа или заменена новой, REJECTED - отклонена модератором/игроком/заменена свежей, APPROVED - деньги подтверждены.
SELECT id, telegram_id, game_key, package_key, exc_amount, rub_amount, status, created_at, decided_at
FROM shop_ruble_orders WHERE telegram_id = 5696809016 ORDER BY created_at DESC;
-- обычные заявки на донат этого игрока (создаются после подтверждения оплаты)
SELECT g.id, g.display_id, g.status, g.game_name, g.item_label, g.gems, g.price_rub, g.exc_paid, g.created_at
FROM gem_purchase_requests g JOIN app_users u ON u.id = g.user_id WHERE u.telegram_id = 5696809016 ORDER BY g.created_at DESC LIMIT 10;
