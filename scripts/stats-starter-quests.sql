-- Выбор «стартовых» квестов для первого входа игрока (простое действие + автопроверка + быстрая награда). Только чтение.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-starter-quests.sql

-- 1) Все доступные сейчас квесты с автопроверкой (active и пакет не приостановлен): по играм, от меньшей награды к большей.
--    Верхние строки каждой игры - кандидаты в стартовые (самая низкая планка).
SELECT q.game_name, q.id, q.title, q.reward_coins AS exc, q.duration_days AS days,
       COALESCE(q.brawl_verify_type, q.clash_royale_verify_type, q.clash_verify_type,
                q.dota_verify_type, q.cs2_verify_type, q.pubg_verify_type) AS verify_type,
       COALESCE(q.brawl_target_count, q.clash_royale_target_count, q.clash_target_count,
                q.dota_target_count, q.cs2_target_count, q.pubg_target_count) AS target,
       q.pack_id, q.one_time_per_account AS one_time
FROM quests q
WHERE q.active = TRUE AND q.pack_suspended = FALSE
  AND COALESCE(q.brawl_verify_type, q.clash_royale_verify_type, q.clash_verify_type,
               q.dota_verify_type, q.cs2_verify_type, q.pubg_verify_type) IS NOT NULL
ORDER BY q.game_name, q.reward_coins, q.title;

-- 2) Как реально проходят квесты за последние 60 дней: сколько взяли, сколько засчитано, сколько минут от взятия до награды (медиана и 90-й процентиль).
--    Отсеивает «красивые на бумаге» квесты, которые на деле долго идут или редко доходят.
SELECT q.game_name, q.title, q.reward_coins AS exc,
       COUNT(*) AS taken,
       SUM(CASE WHEN s.status = 'APPROVED' THEN 1 ELSE 0 END) AS approved,
       ROUND(100.0 * SUM(CASE WHEN s.status = 'APPROVED' THEN 1 ELSE 0 END) / COUNT(*), 0) AS pct_done,
       MEDIAN(CASE WHEN s.status = 'APPROVED' THEN DATEDIFF('MINUTE', s.created_at, s.updated_at) END) AS med_minutes,
       PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY CASE WHEN s.status = 'APPROVED' THEN DATEDIFF('MINUTE', s.created_at, s.updated_at) END) AS p90_minutes
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
WHERE s.status <> 'DRAFT' AND s.created_at >= DATEADD('DAY', -60, CURRENT_TIMESTAMP)
  AND COALESCE(q.brawl_verify_type, q.clash_royale_verify_type, q.clash_verify_type,
               q.dota_verify_type, q.cs2_verify_type, q.pubg_verify_type) IS NOT NULL
GROUP BY q.id, q.game_name, q.title, q.reward_coins
HAVING COUNT(*) >= 5
ORDER BY q.game_name, med_minutes;

-- 3) Мои пять кандидатов: есть ли они, активны ли сейчас, и что с ними по статистике за 60 дней (если взяли < 5 раз, цифры шаткие).
SELECT q.game_name, q.title, q.id, q.active, q.pack_suspended, q.reward_coins AS exc,
       COUNT(s.id) AS taken,
       SUM(CASE WHEN s.status = 'APPROVED' THEN 1 ELSE 0 END) AS approved,
       MEDIAN(CASE WHEN s.status = 'APPROVED' THEN DATEDIFF('MINUTE', s.created_at, s.updated_at) END) AS med_minutes
FROM quests q LEFT JOIN quest_submissions s
       ON s.quest_id = q.id AND s.status <> 'DRAFT' AND s.created_at >= DATEADD('DAY', -60, CURRENT_TIMESTAMP)
WHERE (q.game_name = 'Brawl Stars' AND q.title IN ('Сразись в бою 8 раз', 'Сразись в бою 6 раз'))
   OR (q.game_name = 'Clash Royale' AND q.title IN ('Сыграй 3 боя', 'Сыграй 5 боёв'))
   OR (q.game_name = 'Clash of Clans' AND q.title IN ('Выиграй 2 атаки в мультиплеере', 'Выиграй 3 атаки в мультиплеере'))
   OR (q.game_name = 'CS2' AND q.title IN ('Набери 8 убийств за матч', 'Набери 12 убийств за матч'))
   OR (q.game_name = 'Dota 2' AND q.title = 'Набери 8 убийств за матч')
GROUP BY q.id, q.game_name, q.title, q.active, q.pack_suspended, q.reward_coins
ORDER BY q.game_name, q.title;

-- 4) Порядок игр в выборе: в какие игры новички (регистрация за последние 60 дней) берут ПЕРВЫЙ квест и сколько доводят до награды.
SELECT q.game_name,
       COUNT(*) AS first_quests,
       SUM(CASE WHEN s.status = 'APPROVED' THEN 1 ELSE 0 END) AS approved,
       ROUND(100.0 * SUM(CASE WHEN s.status = 'APPROVED' THEN 1 ELSE 0 END) / COUNT(*), 0) AS pct_done
FROM (
    SELECT s0.user_id, MIN(s0.id) AS first_id
    FROM quest_submissions s0 JOIN app_users u ON u.id = s0.user_id
    WHERE s0.status <> 'DRAFT' AND u.created_at >= DATEADD('DAY', -60, CURRENT_TIMESTAMP)
    GROUP BY s0.user_id
) f
JOIN quest_submissions s ON s.id = f.first_id
JOIN quests q ON q.id = s.quest_id
GROUP BY q.game_name
ORDER BY first_quests DESC;

-- 5) База для сравнения «до»: из всех новичков за последние 60 дней, сколько взяли хоть один квест и сколько получили хоть одну награду.
SELECT COUNT(*) AS new_players,
       SUM(CASE WHEN EXISTS (SELECT 1 FROM quest_submissions s WHERE s.user_id = u.id AND s.status <> 'DRAFT') THEN 1 ELSE 0 END) AS took_quest,
       SUM(CASE WHEN EXISTS (SELECT 1 FROM quest_submissions s WHERE s.user_id = u.id AND s.status = 'APPROVED') THEN 1 ELSE 0 END) AS got_reward
FROM app_users u
WHERE u.created_at >= DATEADD('DAY', -60, CURRENT_TIMESTAMP);
