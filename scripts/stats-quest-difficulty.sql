-- Что реально мешает довести квест Brawl Stars до конца: число боёв, ограничения по бойцам/режимам или «взял и не играл». Только чтение.
-- Окно: заявки, взятые 10-70 дней назад (старше 10 дней - чтобы квесты со сроком до 7-14 дней уже закрылись и не искажали долю).
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-quest-difficulty.sql

-- 0) Страховка: реальные имена колонок Brawl в quests (если ниже упадёт на имени - пришлите этот список).
SELECT column_name FROM information_schema.columns WHERE table_name = 'QUESTS' AND column_name LIKE 'BRAWL%' ORDER BY column_name;

-- 1) По каждому квесту: сколько взяли, сколько засчитано, сколько отменено, сколько из НЕзавершивших вообще не играли (прогресс 0),
--    медиана минут до награды, сколько EXC выплачено всего и на одного взявшего (это и есть цена квеста для фонда).
SELECT q.title, q.brawl_verify_type AS type, q.brawl_target_count AS target, q.reward_coins AS exc, q.duration_days AS days,
       CASE WHEN q.brawl_brawler_names IS NOT NULL AND q.brawl_brawler_names <> '' THEN 'бойцы'
            WHEN q.brawl_mode_keys IS NOT NULL AND q.brawl_mode_keys <> '' THEN 'режимы'
            WHEN q.brawl_require_ranked THEN 'ранговый'
            WHEN q.brawl_require_team THEN 'команда'
            ELSE '-' END AS filter,
       CASE WHEN q.brawl_require_victory THEN 'победа' ELSE 'любой бой' END AS counts,
       COUNT(*) AS taken,
       SUM(CASE WHEN s.status = 'APPROVED' THEN 1 ELSE 0 END) AS done,
       ROUND(100.0 * SUM(CASE WHEN s.status = 'APPROVED' THEN 1 ELSE 0 END) / COUNT(*), 0) AS pct_done,
       SUM(CASE WHEN s.status <> 'APPROVED' AND s.brawl_progress_count = 0 THEN 1 ELSE 0 END) AS not_played,
       MEDIAN(CASE WHEN s.status = 'APPROVED' THEN DATEDIFF('MINUTE', s.created_at, s.updated_at) END) AS med_min,
       SUM(COALESCE(s.awarded_coins, 0)) AS paid_total,
       ROUND(1.0 * SUM(COALESCE(s.awarded_coins, 0)) / COUNT(*), 0) AS paid_per_taker
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
WHERE q.brawl_verify_type IS NOT NULL AND s.status <> 'DRAFT'
  AND s.created_at >= DATEADD('DAY', -70, CURRENT_TIMESTAMP) AND s.created_at <= DATEADD('DAY', -10, CURRENT_TIMESTAMP)
GROUP BY q.id, q.title, q.brawl_verify_type, q.brawl_target_count, q.reward_coins, q.duration_days,
         q.brawl_brawler_names, q.brawl_mode_keys, q.brawl_require_ranked, q.brawl_require_team, q.brawl_require_victory
HAVING COUNT(*) >= 10
ORDER BY q.brawl_verify_type, q.brawl_target_count, pct_done;

-- 2) Главный вопрос: влияет ли число боёв. Только тип BATTLES, сгруппировано по ограничению и размеру цели.
SELECT filter, target_bucket, COUNT(*) AS taken,
       SUM(CASE WHEN status = 'APPROVED' THEN 1 ELSE 0 END) AS done,
       ROUND(100.0 * SUM(CASE WHEN status = 'APPROVED' THEN 1 ELSE 0 END) / COUNT(*), 0) AS pct_done,
       SUM(CASE WHEN status <> 'APPROVED' AND progress = 0 THEN 1 ELSE 0 END) AS not_played,
       MEDIAN(CASE WHEN status = 'APPROVED' THEN minutes END) AS med_min,
       ROUND(1.0 * SUM(paid) / COUNT(*), 0) AS paid_per_taker
FROM (
    SELECT s.status AS status, s.brawl_progress_count AS progress, COALESCE(s.awarded_coins, 0) AS paid,
           DATEDIFF('MINUTE', s.created_at, s.updated_at) AS minutes,
           CASE WHEN q.brawl_brawler_names IS NOT NULL AND q.brawl_brawler_names <> '' THEN '1 бойцы'
                WHEN q.brawl_mode_keys IS NOT NULL AND q.brawl_mode_keys <> '' THEN '2 режимы'
                WHEN q.brawl_require_ranked THEN '3 ранговый'
                WHEN q.brawl_require_team THEN '4 команда'
                ELSE '5 без ограничений' END AS filter,
           CASE WHEN q.brawl_target_count <= 5 THEN 'a: до 5'
                WHEN q.brawl_target_count <= 8 THEN 'b: 6-8'
                WHEN q.brawl_target_count <= 12 THEN 'c: 9-12'
                ELSE 'd: 13+' END AS target_bucket
    FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
    WHERE q.brawl_verify_type = 'BATTLES' AND s.status <> 'DRAFT'
      AND s.created_at >= DATEADD('DAY', -70, CURRENT_TIMESTAMP) AND s.created_at <= DATEADD('DAY', -10, CURRENT_TIMESTAMP)
) x
GROUP BY filter, target_bucket
HAVING COUNT(*) >= 10
ORDER BY filter, target_bucket;

-- 3) Как далеко заходят те, кто не дошёл (BATTLES, прогресс > 0): средняя доля цели. Если ~50% - ступени/короткие квесты помогут,
--    если ~10% - люди бросают в начале и дело в привычке «взял и ушёл», а не в длине.
SELECT COUNT(*) AS not_finished_but_played,
       ROUND(AVG(100.0 * s.brawl_progress_count / q.brawl_target_count), 0) AS avg_pct_of_target,
       ROUND(100.0 * SUM(CASE WHEN 2 * s.brawl_progress_count >= q.brawl_target_count THEN 1 ELSE 0 END) / COUNT(*), 0) AS pct_got_half
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
WHERE q.brawl_verify_type = 'BATTLES' AND s.status <> 'DRAFT' AND s.status <> 'APPROVED' AND s.brawl_progress_count > 0
  AND s.created_at >= DATEADD('DAY', -70, CURRENT_TIMESTAMP) AND s.created_at <= DATEADD('DAY', -10, CURRENT_TIMESTAMP);

-- 4) Общая картина: из всех, кто брал квесты Brawl Stars - сколько не сыграли ни одного боя после взятия.
SELECT COUNT(*) AS taken,
       SUM(CASE WHEN s.status = 'APPROVED' THEN 1 ELSE 0 END) AS done,
       SUM(CASE WHEN s.status <> 'APPROVED' AND s.brawl_progress_count = 0 THEN 1 ELSE 0 END) AS never_played,
       ROUND(100.0 * SUM(CASE WHEN s.status <> 'APPROVED' AND s.brawl_progress_count = 0 THEN 1 ELSE 0 END) / COUNT(*), 0) AS pct_never_played
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
WHERE q.brawl_verify_type = 'BATTLES' AND s.status <> 'DRAFT'
  AND s.created_at >= DATEADD('DAY', -70, CURRENT_TIMESTAMP) AND s.created_at <= DATEADD('DAY', -10, CURRENT_TIMESTAMP);
