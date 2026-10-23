-- «Игроку не засчитали задания дня / бонус» - диагностика по одному игроку. Только чтение.
-- Замените 6623914647 на Telegram ID игрока (в обоих запросах). Запуск: cd /root/gamebot && git pull origin main && bash scripts/run-sql.sh scripts/check-player-daily-tasks.sql
-- Дни считаются по UTC (время сервера; по Москве +3 ч, по UTC+7 +7 ч): «сегодня» игрока в боте может отличаться от «сегодня» сервера.
-- Как читать первый запрос:
--  daily_tasks_date не сегодняшняя дата сервера -> отметки за сегодня ещё не ставились (все три задания считаются с нуля).
--  daily_tasks_mask: 1 = забрал ежедневный бонус, 2 = выполнил квест, 4 = открыл сундук дня; 7 = все три (сундук заданий готов).
--  daily_tasks_claimed_date = сегодня -> сундук заданий уже получен.
--  last_bonus_date / last_chest_opened_date = сегодня -> бонус/сундук за сегодня были, а бита в маске нет -> отметка потерялась (гонка записи профиля).

SELECT u.id AS user_id, u.telegram_id, u.nickname, CURRENT_DATE AS server_today,
       u.daily_tasks_date, u.daily_tasks_mask, u.daily_tasks_claimed_date,
       u.last_bonus_date, u.last_chest_opened_date, u.streak_days, u.last_activity_date, u.completed_quests
FROM app_users u WHERE u.telegram_id = 6623914647;

-- Начисления игрока за последние 2 суток (UTC): ежедневный бонус (DAILY), сундук, квесты, сундук заданий
SELECT t.created_at, t.type, t.amount, t.description
FROM exc_transactions t JOIN app_users u ON u.id = t.user_id
WHERE u.telegram_id = 6623914647 AND t.created_at >= DATEADD('DAY', -2, CURRENT_TIMESTAMP)
ORDER BY t.created_at;
