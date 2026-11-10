-- Когда меняются пачки квестов: расписание ротации по играм (включено ли, цикл, день недели, час по Москве, ближайшая смена) и какая пачка сейчас активна. Только чтение.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-pack-schedule.sql
-- 1) расписание: day_of_week 1=пн ... 7=вс, hour - по Москве, next_switch_at - ближайшая смена (МСК)
SELECT s.game_name, s.enabled, s.cycle_days, s.day_of_week, s."HOUR" AS hour_msk, CAST(s.next_switch_at AS TIMESTAMP(0)) AS next_switch_msk
FROM quest_pack_schedules s ORDER BY s.game_name;
-- 2) пачки игр: какая активна и сколько в ней квестов
SELECT p.game_name, p.name, p.active, (SELECT COUNT(*) FROM quests q WHERE q.pack_id = p.id) AS quests, CAST(p.created_at AS TIMESTAMP(0)) AS created
FROM quest_packs p WHERE LOWER(p.game_name) = 'cs2' ORDER BY p.id;
