-- Диагностика «квест Clash Royale не засчитывается» по одному игроку: тег и что бот записал как базу и прогресс. Только чтение.
-- Замените 5031454177 на Telegram ID игрока (2 места). Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-player-royale.sql
-- Дальше: тег из первой таблицы -> bash scripts/probe-supercell-api.sh - ТЕГ -   (покажет, что реально отдаёт API: поле trophies и т.д.)
-- Как читать: база = значение trophies/wins/... при первом опросе после взятия квеста; прогресс = текущее минус база.
-- Прогресс 0 при реальном росте в игре -> API отдаёт для этого поля устаревшее/ограниченное значение.

SELECT u.id AS user_id, u.nickname, u.clash_royale_tag, u.clash_royale_tag_confirmed_at, u.created_at AS registered_at
FROM app_users u WHERE u.telegram_id = 5031454177;

SELECT s.id, s.created_at AS taken_at, s.updated_at, s.status, s.clash_royale_baseline_value AS base, s.clash_royale_progress_count AS progress,
       q.clash_royale_target_count AS target, q.clash_royale_verify_type AS verify_type, q.title
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id JOIN app_users u ON u.id = s.user_id
WHERE u.telegram_id = 5031454177 AND q.clash_royale_verify_type IS NOT NULL
ORDER BY s.id DESC;
