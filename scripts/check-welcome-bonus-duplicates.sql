-- Дубли приветственного бонуса (WELCOME_BONUS): у игрока 6623914647 в журнале две записи по +200 EXC с разницей 0,3 с, а баланс вырос только на 200.
-- Только чтение. Запуск: cd /root/gamebot && git pull origin main && bash scripts/run-sql.sh scripts/check-welcome-bonus-duplicates.sql

-- 1) Журнал этого игрока по порядку: balance_after покажет, учтена ли вторая запись в балансе
SELECT t.created_at, t.type, t.amount, t.balance_after
FROM exc_transactions t JOIN app_users u ON u.id = t.user_id
WHERE u.telegram_id = 6623914647 ORDER BY t.created_at LIMIT 8;

-- 2) Сколько игроков вообще имеют больше одной записи WELCOME_BONUS (масштаб проблемы)
SELECT COUNT(*) AS players_with_dupes, COALESCE(SUM(c - 1), 0) AS extra_records
FROM (SELECT user_id, COUNT(*) AS c FROM exc_transactions WHERE type = 'WELCOME_BONUS' GROUP BY user_id HAVING COUNT(*) > 1);

-- 3) Последние 15 таких игроков (даты, чтобы понять, давно ли и повторяется ли сейчас)
SELECT u.telegram_id, COUNT(*) AS records, MIN(t.created_at) AS first_at, MAX(t.created_at) AS last_at
FROM exc_transactions t JOIN app_users u ON u.id = t.user_id
WHERE t.type = 'WELCOME_BONUS' GROUP BY u.telegram_id HAVING COUNT(*) > 1 ORDER BY MAX(t.created_at) DESC LIMIT 15;
