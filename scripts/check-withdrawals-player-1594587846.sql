-- Разовая проверка: заявки на вывод игрока 1594587846 (Pionex UID, 22 240 EXC): статус, номер, реквизиты. Только читает базу.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-withdrawals-player-1594587846.sql
SELECT r.id, r.display_id, r.status, r.created_at, ri.title, ri.category, r.payout_details, r.paid_price_coins
FROM reward_requests r JOIN reward_items ri ON ri.id = r.reward_id JOIN app_users u ON u.id = r.user_id
WHERE u.telegram_id = 1594587846 ORDER BY r.created_at DESC LIMIT 10;

-- Все ожидающие выводы по способам (как их видит список модератора)
SELECT r.display_id, r.status, r.created_at, ri.title
FROM reward_requests r JOIN reward_items ri ON ri.id = r.reward_id
WHERE ri.category = 'Вывод' AND r.status = 'PENDING' ORDER BY r.created_at;
