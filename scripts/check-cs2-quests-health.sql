-- Работают ли квесты CS2 после сбоя Steam 25.11.2026. Только чтение. Игроков по никам не выводит.
-- Запуск на сервере: cd /root/gamebot && git pull origin main && bash scripts/run-sql.sh scripts/check-cs2-quests-health.sql

-- 1. По каждому квесту, который открыт сейчас или который брали/засчитывали с 25.11:
-- taken - взяли с 25.11; approved - засчитано с 25.11 (включая взятые раньше);
-- in_work - в процессе сейчас (все даты); moving - из них с прогрессом больше 0 (для квестов «за матч» прогресс не ведётся, всегда 0).
SELECT q.title, q.cs2verify_type AS check_type, q.active,
       SUM(CASE WHEN s.created_at >= TIMESTAMP '2026-11-25 00:00:00' THEN 1 ELSE 0 END) AS taken,
       SUM(CASE WHEN s.status = 'APPROVED' AND s.updated_at >= TIMESTAMP '2026-11-25 00:00:00' THEN 1 ELSE 0 END) AS approved,
       SUM(CASE WHEN s.status = 'DRAFT' AND (s.expires_at IS NULL OR s.expires_at > CURRENT_TIMESTAMP) THEN 1 ELSE 0 END) AS in_work,
       SUM(CASE WHEN s.status = 'DRAFT' AND (s.expires_at IS NULL OR s.expires_at > CURRENT_TIMESTAMP) AND s.cs2progress_count > 0 THEN 1 ELSE 0 END) AS moving,
       MAX(CASE WHEN s.status = 'APPROVED' THEN s.updated_at END) AS last_approved
FROM quests q LEFT JOIN quest_submissions s ON s.quest_id = q.id
WHERE q.game_name = 'CS2' AND q.cs2verify_type IS NOT NULL
GROUP BY q.id, q.title, q.cs2verify_type, q.active
HAVING q.active = TRUE
    OR SUM(CASE WHEN s.created_at >= TIMESTAMP '2026-11-25 00:00:00' THEN 1 ELSE 0 END) > 0
    OR SUM(CASE WHEN s.status = 'APPROVED' AND s.updated_at >= TIMESTAMP '2026-11-25 00:00:00' THEN 1 ELSE 0 END) > 0
ORDER BY q.active DESC, approved DESC, taken DESC;

-- 2. Заявки в процессе: baseline пустой - бот ещё ни разу не получил статистику игрока (или у него не привязан Steam).
-- Для квестов «за матч» отрицательный baseline - снят новым способом (после исправления 25.11).
SELECT s.id AS submission_id, q.title, q.cs2verify_type AS check_type, q.cs2target_count AS target,
       s.cs2progress_count AS progress, s.cs2baseline_value AS baseline,
       CASE WHEN u.cs2steam_id64 IS NULL THEN 'NO' ELSE 'YES' END AS steam_linked, s.created_at, s.expires_at
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id JOIN app_users u ON u.id = s.user_id
WHERE q.cs2verify_type IS NOT NULL AND s.status = 'DRAFT' AND (s.expires_at IS NULL OR s.expires_at > CURRENT_TIMESTAMP)
ORDER BY s.created_at DESC LIMIT 60;

-- 3. Какие наборы CS2 и когда включались, расписание смены (время по Москве).
SELECT pack_name, switched_at, source, activated, hidden FROM quest_pack_switch_log WHERE game_name = 'CS2' ORDER BY switched_at DESC LIMIT 5;
SELECT game_name, enabled, cycle_days, last_switch_at, next_switch_at FROM quest_pack_schedules WHERE game_name = 'CS2';

-- 4. Разрешены ли квесты «за матч» настройкой (пустой результат = нет, скрываются при каждом запуске).
SELECT setting_key, setting_value FROM app_settings WHERE setting_key = 'cs2.lastmatch.enabled';
