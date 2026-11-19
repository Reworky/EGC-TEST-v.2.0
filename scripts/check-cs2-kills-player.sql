-- «25 убийств за матч в CS2 не засчитали» - диагностика одного игрока. Только чтение. Игрок: telegram_id 1459489472 (Kurapov @Makarek1802); для другого заменить число во всех запросах.
-- Запуск на сервере: cd /root/gamebot && git pull origin main && bash scripts/run-sql.sh scripts/check-cs2-kills-player.sql
-- Что смотрим: привязан ли Steam, какой квест «за матч» он взял и в каком он статусе, и есть ли в базе квесты на убийства за матч с таким или большим порогом.
-- Поле progress у квестов LAST_MATCH_* - лучшее найденное значение последнего матча при проверке; baseline - число сыгранных матчей на момент последней проверки.

-- 1) Игрок и привязка Steam (без привязки или с закрытым профилем авто-проверка ничего не видит)
SELECT u.id AS user_id, u.telegram_id, u.nickname, u.coins, u.cs2steam_id64 AS steam_id64, u.cs2linked_at
FROM app_users u WHERE u.telegram_id = 1459489472;

-- 2) Его CS2-заявки (самые свежие сверху): статус, порог, прогресс
SELECT s.id AS submission_id, q.title, q.cs2verify_type AS verify_type, q.cs2target_count AS target, s.status,
       s.cs2baseline_value AS baseline, s.cs2progress_count AS progress, s.created_at, s.updated_at
FROM quest_submissions s
JOIN quests q ON q.id = s.quest_id
JOIN app_users u ON u.id = s.user_id
WHERE u.telegram_id = 1459489472 AND q.cs2verify_type IS NOT NULL
ORDER BY s.created_at DESC;

-- 3) Активные квесты CS2 на убийства (за матч и накопительные): какой порог какой квест требует
SELECT q.id, q.title, q.cs2verify_type AS verify_type, q.cs2target_count AS target, q.reward_coins, q.active
FROM quests q
WHERE q.cs2verify_type IN ('LAST_MATCH_KILLS', 'KILLS') AND q.active = TRUE
ORDER BY q.cs2verify_type, q.cs2target_count;
