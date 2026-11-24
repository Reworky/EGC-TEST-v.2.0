-- Диагностика квеста Brawl Stars «с другом» одного игрока (только чтение). Игрок: @lolkapopio; для другого - заменить ник во всех строках ниже.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-brawl-partner-player.sql
-- partner_tag - тег друга, выбранный при взятии квеста (NULL = ни один бой не засчитается); cursor - время последнего учтённого боя (UTC); progress - сколько боёв засчитано.

SELECT u.id AS user_id, u.telegram_id, u.telegram_username, u.brawl_stars_tag
FROM app_users u WHERE LOWER(u.telegram_username) = 'lolkapopio';

SELECT s.id AS submission_id, q.title, q.brawl_verify_type, q.brawl_target_count AS target, s.status,
       s.brawl_partner_tag AS partner_tag, s.brawl_progress_count AS progress, s.brawl_battle_cursor AS cursor,
       s.created_at, s.updated_at, s.expires_at
FROM quest_submissions s
JOIN quests q ON q.id = s.quest_id
JOIN app_users u ON u.id = s.user_id
WHERE LOWER(u.telegram_username) = 'lolkapopio' AND q.brawl_verify_type IS NOT NULL
ORDER BY s.created_at DESC LIMIT 10;
