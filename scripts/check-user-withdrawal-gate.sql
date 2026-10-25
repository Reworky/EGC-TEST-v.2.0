-- Почему у игрока не открыт вывод: дата регистрации, квесты, заморозка. Только чтение.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-user-withdrawal-gate.sql
-- Ник игрока (его часть, без @) поменяйте в LIKE '%...%' ниже. Сервер в UTC, Москва = +3 ч.
SELECT telegram_id,
       telegram_username,
       created_at                                    AS registered_utc,
       DATEADD('DAY', 3, created_at)                 AS withdrawal_opens_utc,
       DATEDIFF('MINUTE', CURRENT_TIMESTAMP, DATEADD('DAY', 3, created_at)) / 60.0 AS hours_left,
       completed_quests,
       withdrawal_frozen,
       blocked,
       coins
FROM app_users
WHERE LOWER(telegram_username) LIKE '%beyondbirthday%';
