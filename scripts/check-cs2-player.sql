-- Диагностика CS2-квестов одного игрока (только чтение). Игрок: telegram_id 7697900426 (zenkkuu); для другого - заменить число во всех строках ниже.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-cs2-player.sql
-- baseline у кумулятивных квестов (убийства в голову и т.п.) - значение счётчика Steam на момент взятия, у «за матч» (LAST_MATCH_*) - число сыгранных матчей на момент последней проверки.

SELECT s.id AS submission_id, q.title, q.cs2_verify_type AS verify_type, q.cs2_target_count AS target, s.status,
       s.cs2_baseline_value AS baseline, s.cs2_progress_count AS progress, s.created_at, s.updated_at
FROM quest_submissions s
JOIN quests q ON q.id = s.quest_id
JOIN app_users u ON u.id = s.user_id
WHERE u.telegram_id = 7697900426 AND q.cs2_verify_type IS NOT NULL
ORDER BY s.created_at DESC;
