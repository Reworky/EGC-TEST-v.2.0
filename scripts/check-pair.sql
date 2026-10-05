-- Сравнение двух подозрительных аккаунтов (общие реквизиты): кто кого пригласил, один ли отряд/тег/телефон, когда зарегистрированы, были ли выплаты.
-- Подставьте Telegram ID в обе строки ниже (6840572161 и 6523363420 — заявка В-… от 05.10.2026).
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-pair.sql
SELECT telegram_id, nickname, telegram_username, created_at, referred_by_telegram_id, phone_number,
       brawl_stars_tag, squad_id, coins, completed_quests, fraud_suspect, blocked
FROM app_users
WHERE telegram_id IN (6840572161, 6523363420);

SELECT u.telegram_id, r.id AS request_id, r.status, r.created_at, r.paid_price_coins, r.payout_details
FROM reward_requests r JOIN app_users u ON u.id = r.user_id
WHERE u.telegram_id IN (6840572161, 6523363420) AND r.payout_details IS NOT NULL
ORDER BY r.created_at DESC;
