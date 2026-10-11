-- Диагностика «серия под угрозой, а бонус/задания пропали» по одному игроку. Только чтение.
-- Замените 1041652225 на Telegram ID игрока (во ВСЕХ трёх запросах). Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-player-streak.sql
-- Дни считаются по UTC (время сервера). Как читать:
--  last_activity_date = вчера и last_bonus_date не сегодня -> серия ещё жива, бонус доступен (до 00:00 UTC).
--  last_activity_date = сегодня, last_bonus_date = сегодня -> бонус уже получен, серия сохранена (предупреждение было ложным или игрок забрал бонус до/после него).
--  last_broken_streak_days заполнен -> серия УЖЕ сгорела (снимок для восстановления): вместо обычного бонуса игроку предлагают «Восстановить за ⭐ / Начать заново».
--  daily_tasks_date = сегодня -> отметки заданий дня за сегодня есть; другая дата -> задания считаются «с нуля».

SELECT u.id AS user_id, u.nickname, u.streak_days, u.last_activity_date, u.last_bonus_date,
       u.last_broken_streak_days, u.last_broken_streak_until,
       u.daily_tasks_date, u.daily_tasks_mask, u.daily_tasks_claimed_date,
       u.last_bot_activity_at, u.egc_pass_streak_save_date,
       CURRENT_DATE AS server_today_utc, CURRENT_TIMESTAMP AS server_now
FROM app_users u WHERE u.telegram_id = 1041652225;

-- Что начислялось игроку за последние 3 дня (ежедневный бонус, сундук, квесты, награды за задания дня): по времени.
SELECT t.created_at, t.type, t.amount, t.description
FROM exc_transactions t JOIN app_users u ON u.id = t.user_id
WHERE u.telegram_id = 1041652225 AND t.created_at >= DATEADD('DAY', -3, CURRENT_TIMESTAMP)
ORDER BY t.created_at DESC
LIMIT 40;

-- Какие напоминания игроку отправлялись за последние 3 дня (STREAK_AT_RISK = «серия под угрозой», STREAK_BROKEN = «серия прервалась»).
SELECT n.sent_at, n.type, n.returned_at
FROM notification_log n JOIN app_users u ON u.id = n.user_id
WHERE u.telegram_id = 1041652225 AND n.sent_at >= DATEADD('DAY', -3, CURRENT_TIMESTAMP)
ORDER BY n.sent_at DESC
LIMIT 20;
