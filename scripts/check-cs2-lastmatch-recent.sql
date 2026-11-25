-- CS2 «за матч»: заявки последних 4 дней по активным квестам (статус, когда взята, когда обновлена, замер). Только чтение.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-cs2-lastmatch-recent.sql
SELECT q.title, s.id, s.status, s.created_at, s.updated_at, s.cs2baseline_value
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
WHERE q.game_name = 'CS2' AND q.cs2verify_type LIKE 'LAST_MATCH%' AND s.created_at >= DATEADD('DAY', -4, CURRENT_TIMESTAMP)
ORDER BY s.created_at DESC;
