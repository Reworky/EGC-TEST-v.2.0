-- Закрывались ли когда-нибудь CS2-квесты «за один матч» (LAST_MATCH_*): сколько заявок взято / одобрено, когда последнее одобрение. Только чтение.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-cs2-lastmatch-approved.sql
SELECT q.title, q.cs2verify_type AS type, q.active,
       COUNT(s.id) AS taken,
       SUM(CASE WHEN s.status = 'APPROVED' THEN 1 ELSE 0 END) AS approved,
       MAX(CASE WHEN s.status = 'APPROVED' THEN s.updated_at END) AS last_approved
FROM quests q LEFT JOIN quest_submissions s ON s.quest_id = q.id
WHERE q.game_name = 'CS2' AND q.cs2verify_type LIKE 'LAST_MATCH%'
GROUP BY q.id, q.title, q.cs2verify_type, q.active
ORDER BY approved DESC, taken DESC;
