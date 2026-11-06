-- Стирает у игрока brokengame все заявки по партнёрскому квесту «ОТП Банк» и обнуляет партнёрский запас вывода:
-- бот перестанет считать его выполнившим (hasApprovedPartnerQuest, бейдж «Банкир EGC», рублёвый вывод сверх порога), квест снова можно взять.
-- Баланс EXC и история операций НЕ меняются. Сначала посмотрите scripts/check-otp-brokengame.sql: строка игрока должна быть одна.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/reset-otp-brokengame.sql
DELETE FROM quest_submissions WHERE quest_id IN (SELECT id FROM quests WHERE game_name = 'ОТП Банк')
  AND user_id IN (SELECT id FROM app_users WHERE LOWER(REPLACE(COALESCE(telegram_username, ''), '@', '')) = 'brokengame' OR LOWER(nickname) = 'brokengame');
UPDATE app_users SET partner_withdrawal_allowance_exc = 0
WHERE LOWER(REPLACE(COALESCE(telegram_username, ''), '@', '')) = 'brokengame' OR LOWER(nickname) = 'brokengame';
SELECT 'после: заявок ОТП у игрока' AS check_name, COUNT(*) AS n FROM quest_submissions s JOIN quests q ON q.id = s.quest_id JOIN app_users u ON u.id = s.user_id
WHERE q.game_name = 'ОТП Банк' AND (LOWER(REPLACE(COALESCE(u.telegram_username, ''), '@', '')) = 'brokengame' OR LOWER(u.nickname) = 'brokengame');
