-- Что есть у игрока brokengame по партнёрскому квесту «ОТП Банк». Только чтение.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-otp-brokengame.sql
-- 1) кто найден (по @username или нику): убедитесь, что строка ровно одна и это нужный игрок
SELECT id, telegram_id, telegram_username, nickname, coins, completed_quests, partner_withdrawal_allowance_exc
FROM app_users WHERE LOWER(REPLACE(COALESCE(telegram_username, ''), '@', '')) = 'brokengame' OR LOWER(nickname) = 'brokengame';
-- 2) его заявки по квесту ОТП (эти строки удалит reset-otp-brokengame.sql)
SELECT s.id, s.display_id, s.status, s.created_at, s.updated_at, s.bonus_paid_at, q.title
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id JOIN app_users u ON u.id = s.user_id
WHERE q.game_name = 'ОТП Банк' AND (LOWER(REPLACE(COALESCE(u.telegram_username, ''), '@', '')) = 'brokengame' OR LOWER(u.nickname) = 'brokengame')
ORDER BY s.created_at DESC;
-- 3) начисления за квест и бонус в истории EXC (их скрипт НЕ откатывает, баланс остаётся как есть)
SELECT t.id, t.created_at, t.amount, t.type, t.description
FROM exc_transactions t JOIN app_users u ON u.id = t.user_id
WHERE (LOWER(REPLACE(COALESCE(u.telegram_username, ''), '@', '')) = 'brokengame' OR LOWER(u.nickname) = 'brokengame')
  AND (LOWER(t.description) LIKE '%отп%' OR LOWER(t.description) LIKE '%партн%') ORDER BY t.created_at DESC;
