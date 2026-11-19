-- Где квест «Набери 50 убийств выстрелом в голову» (CS2): в какой пачке, активен ли. Только чтение.
-- Запуск: cd /root/gamebot && git pull origin main && bash scripts/run-sql.sh scripts/check-cs2-headshot-quest.sql
-- Если квест есть, но active = FALSE или пачка не активна (quest_packs.active = FALSE), игрок его не видит: пачку сменили (по расписанию или вручную) или квест выключили.

-- 1) все квесты CS2 «в голову»: пачка, активность
SELECT q.id, q.title, q.category, q.active AS quest_active, p.name AS pack, p.active AS pack_active, q.cs2verify_type AS verify_type, q.cs2target_count AS target
FROM quests q LEFT JOIN quest_packs p ON p.id = q.pack_id
WHERE q.game_name = 'CS2' AND LOWER(q.title) LIKE '%в голову%'
ORDER BY q.id;

-- 2) сколько квестов CS2 активно сейчас по пачкам
SELECT COALESCE(p.name, '(без пачки)') AS pack, p.active AS pack_active, COUNT(*) AS active_quests
FROM quests q LEFT JOIN quest_packs p ON p.id = q.pack_id
WHERE q.game_name = 'CS2' AND q.active = TRUE
GROUP BY p.name, p.active ORDER BY p.name;
