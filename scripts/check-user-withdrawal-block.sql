-- Почему игрок не может вывести: заморозка вывода, профиль (страна, возраст, телефон), возраст аккаунта и число квестов, дубли по устройству/нику. Только чтение.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-user-withdrawal-block.sql
-- Ник игрока (без @) - в двух строках WHERE: сейчас bigbilly06. Сервер в UTC, Москва = +3 ч.
-- 1) причины блока: withdrawal_frozen (ставит только админ командой /wdfreeze), заполненность профиля, 3 дня после регистрации и 3 квеста
SELECT u.telegram_id, u.telegram_username, u.nickname, u.country, u.age, u.phone_number IS NOT NULL AS has_phone,
  u.withdrawal_frozen, u.blocked, u.fraud_suspect, u.created_at, u.completed_quests, u.coins, u.xp
FROM app_users u WHERE LOWER(REPLACE(COALESCE(u.telegram_username, ''), '@', '')) = 'bigbilly06';
-- 2) последние заявки на вывод и награды этого игрока (были ли выплаты до заморозки)
SELECT r.display_id, r.status, r.created_at, i.title, COALESCE(r.paid_price_coins, i.price_coins) AS exc
FROM reward_requests r JOIN reward_items i ON i.id = r.reward_id JOIN app_users u ON u.id = r.user_id
WHERE LOWER(REPLACE(COALESCE(u.telegram_username, ''), '@', '')) = 'bigbilly06' ORDER BY r.created_at DESC LIMIT 15;
