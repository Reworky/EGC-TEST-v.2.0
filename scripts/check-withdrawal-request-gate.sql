-- Как заявка на вывод прошла при <3 квестов: счётчик app_users.completed_quests против реальных одобренных квестов,
-- статусы всех попыток и даты заявок. Только чтение. Номер заявки/игрока поменяйте ниже.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-withdrawal-request-gate.sql
-- 1) Счётчик против факта
SELECT u.telegram_id, u.telegram_username, u.created_at AS registered_utc, u.completed_quests AS counter,
       (SELECT COUNT(*) FROM quest_submissions s WHERE s.user_id = u.id AND s.status = 'APPROVED') AS approved_now,
       u.withdrawal_frozen, u.fraud_suspect
FROM app_users u WHERE u.telegram_id = 8852272498;
-- 2) Все квест-заявки игрока: что одобрено, что отклонено/отозвано и когда
SELECT s.display_id, s.status, s.created_at, s.updated_at, q.title
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id JOIN app_users u ON u.id = s.user_id
WHERE u.telegram_id = 8852272498 ORDER BY s.created_at;
-- 3) Заявки на вывод и покупки игрока: даты (до/после запуска правила 3 квеста + 3 дня, 2026-10-03)
SELECT r.display_id, r.status, r.created_at, r.paid_at, r.paid_price_coins, i.title, i.category
FROM reward_requests r JOIN reward_items i ON i.id = r.reward_item_id JOIN app_users u ON u.id = r.user_id
WHERE u.telegram_id = 8852272498 ORDER BY r.created_at;
