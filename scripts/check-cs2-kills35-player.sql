-- Диагностика CS2-квестов «за матч» одного игрока (только чтение). Игрок: @Makarek1802; для другого - заменить ник во всех строках ниже.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-cs2-kills35-player.sql
-- baseline у LAST_MATCH_* типов - число матчей (total_matches_played) на момент последней проверки; progress у них не используется.

SELECT u.id AS user_id, u.telegram_id, u.telegram_username, u.cs2steam_id64 AS steam_id64, u.cs2linked_at
FROM app_users u WHERE LOWER(u.telegram_username) = 'makarek1802';

SELECT s.id AS submission_id, q.title, q.cs2verify_type AS verify_type, q.cs2target_count AS target, s.status,
       s.cs2baseline_value AS baseline, s.cs2progress_count AS progress, s.created_at, s.updated_at, s.expires_at
FROM quest_submissions s
JOIN quests q ON q.id = s.quest_id
JOIN app_users u ON u.id = s.user_id
WHERE LOWER(u.telegram_username) = 'makarek1802' AND q.cs2verify_type IS NOT NULL
ORDER BY s.created_at DESC LIMIT 15;
