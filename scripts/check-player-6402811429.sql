-- Разовая проверка игрока 6402811429 (заявка на звёзды 05.11): как он прошёл проверку «3 квеста», откуда EXC. Только читает базу.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-player-6402811429.sql
SELECT telegram_id, created_at, completed_quests, coins, withdrawal_frozen, blocked, phone_number IS NOT NULL AS has_phone
FROM app_users WHERE telegram_id = 6402811429;

SELECT s.id, s.status, s.created_at, q.title, q.game_name
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id JOIN app_users u ON u.id = s.user_id
WHERE u.telegram_id = 6402811429 ORDER BY s.created_at;

SELECT t.type, COUNT(*) AS n, SUM(t.amount) AS exc_sum
FROM exc_transactions t JOIN app_users u ON u.id = t.user_id
WHERE u.telegram_id = 6402811429 GROUP BY t.type ORDER BY exc_sum DESC;
