-- Brawl Stars: сколько EXC и XP в сутки получает типичный активный игрок (для расчёта общего дневного бюджета «бесконечного режима»).
-- Только чтение. Окно - последние 14 полных суток (сегодняшний день не берём). Учитываются все засчитанные квесты Brawl Stars, спонсорские - нет.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-bs-daily-earnings.sql

-- 1) По дням: сколько игроков, прохождений и EXC/XP выплачено по Brawl Stars.
SELECT CAST(s.updated_at AS DATE) AS dt,
       COUNT(DISTINCT s.user_id) AS players,
       COUNT(*) AS approved,
       SUM(COALESCE(s.awarded_coins, 0)) AS exc_paid,
       SUM(COALESCE(s.awarded_xp, 0)) AS xp_paid
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
WHERE q.game_name = 'Brawl Stars' AND q.sponsored = FALSE AND s.status = 'APPROVED'
  AND s.updated_at >= DATEADD('DAY', -14, CURRENT_DATE) AND s.updated_at < CURRENT_DATE
GROUP BY CAST(s.updated_at AS DATE)
ORDER BY dt;

-- 2) ГЛАВНОЕ: сколько EXC/XP получает активный игрок за СУТКИ (одна строка = игрок-день, в который он что-то получил по Brawl Stars).
--    avg - среднее, median - типичный, p75/p90 - «сильные» дни, max - потолок; per_day_quests - сколько квестов он закрывает за такой день.
SELECT COUNT(*) AS player_days,
       ROUND(AVG(exc), 0) AS avg_exc,
       ROUND(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY exc), 0) AS median_exc,
       ROUND(PERCENTILE_CONT(0.75) WITHIN GROUP (ORDER BY exc), 0) AS p75_exc,
       ROUND(PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY exc), 0) AS p90_exc,
       MAX(exc) AS max_exc,
       ROUND(AVG(xp), 0) AS avg_xp,
       ROUND(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY xp), 0) AS median_xp,
       ROUND(PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY xp), 0) AS p90_xp,
       ROUND(AVG(cnt), 2) AS avg_quests_per_day
FROM (
    SELECT s.user_id, CAST(s.updated_at AS DATE) AS d,
           SUM(COALESCE(s.awarded_coins, 0)) AS exc,
           SUM(COALESCE(s.awarded_xp, 0)) AS xp,
           COUNT(*) AS cnt
    FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
    WHERE q.game_name = 'Brawl Stars' AND q.sponsored = FALSE AND s.status = 'APPROVED'
      AND s.updated_at >= DATEADD('DAY', -14, CURRENT_DATE) AND s.updated_at < CURRENT_DATE
    GROUP BY s.user_id, CAST(s.updated_at AS DATE)
) t;

-- 3) Распределение игрок-дней по сумме EXC за сутки (где «толпа» и где хвост).
SELECT CASE WHEN exc <= 1500 THEN '1) до 1 500'
            WHEN exc <= 2500 THEN '2) 1 501-2 500'
            WHEN exc <= 4000 THEN '3) 2 501-4 000'
            WHEN exc <= 6000 THEN '4) 4 001-6 000'
            ELSE '5) больше 6 000' END AS exc_per_day,
       COUNT(*) AS player_days,
       SUM(exc) AS exc_total
FROM (
    SELECT s.user_id, CAST(s.updated_at AS DATE) AS d, SUM(COALESCE(s.awarded_coins, 0)) AS exc
    FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
    WHERE q.game_name = 'Brawl Stars' AND q.sponsored = FALSE AND s.status = 'APPROVED'
      AND s.updated_at >= DATEADD('DAY', -14, CURRENT_DATE) AND s.updated_at < CURRENT_DATE
    GROUP BY s.user_id, CAST(s.updated_at AS DATE)
) t
GROUP BY CASE WHEN exc <= 1500 THEN '1) до 1 500'
              WHEN exc <= 2500 THEN '2) 1 501-2 500'
              WHEN exc <= 4000 THEN '3) 2 501-4 000'
              WHEN exc <= 6000 THEN '4) 4 001-6 000'
              ELSE '5) больше 6 000' END
ORDER BY exc_per_day;

-- 4) Среднее и медиана в день ПО ИГРОКУ за окно (игрок с 2 днями по 2000 даёт 2000, а не 4000): типичный «вклад» игрока в день активности.
SELECT COUNT(*) AS players,
       ROUND(AVG(avg_exc_per_active_day), 0) AS avg_of_players,
       ROUND(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY avg_exc_per_active_day), 0) AS median_of_players,
       ROUND(PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY avg_exc_per_active_day), 0) AS p90_of_players,
       ROUND(AVG(active_days), 1) AS avg_active_days
FROM (
    SELECT user_id, AVG(exc) AS avg_exc_per_active_day, COUNT(*) AS active_days
    FROM (
        SELECT s.user_id, CAST(s.updated_at AS DATE) AS d, SUM(COALESCE(s.awarded_coins, 0)) AS exc
        FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
        WHERE q.game_name = 'Brawl Stars' AND q.sponsored = FALSE AND s.status = 'APPROVED'
          AND s.updated_at >= DATEADD('DAY', -14, CURRENT_DATE) AND s.updated_at < CURRENT_DATE
        GROUP BY s.user_id, CAST(s.updated_at AS DATE)
    ) x
    GROUP BY user_id
) p;

-- 5) Концентрация: какую долю всех EXC по Brawl Stars за окно получают 10 самых «богатых» игроков (чтобы потолок не ориентировался на них).
SELECT COUNT(*) AS top_players, SUM(exc) AS exc_top,
       (SELECT SUM(COALESCE(s.awarded_coins, 0)) FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
        WHERE q.game_name = 'Brawl Stars' AND q.sponsored = FALSE AND s.status = 'APPROVED'
          AND s.updated_at >= DATEADD('DAY', -14, CURRENT_DATE) AND s.updated_at < CURRENT_DATE) AS exc_all
FROM (
    SELECT s.user_id, SUM(COALESCE(s.awarded_coins, 0)) AS exc
    FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
    WHERE q.game_name = 'Brawl Stars' AND q.sponsored = FALSE AND s.status = 'APPROVED'
      AND s.updated_at >= DATEADD('DAY', -14, CURRENT_DATE) AND s.updated_at < CURRENT_DATE
    GROUP BY s.user_id
    ORDER BY exc DESC
    LIMIT 10
) t;
