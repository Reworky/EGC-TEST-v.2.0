-- Ежедневная привычка: сундук дня, ежедневный бонус и квесты - сколько игроков, сколько EXC, как они пересекаются. Только чтение.
-- Нужен, чтобы спроектировать «дневные задания → сундук» (сколько платить, что считать заданием, какой потолок).
-- Действия игрока берутся из exc_transactions (type QUEST / DAILY / CHEST / AD_REWARD, amount > 0), окно - 30 дней, время сервера.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-daily-loop.sql

-- 1) По дням: сколько игроков забрали ежедневный бонус / открыли сундук / получили награду за квест и сколько EXC выдано на каждый вид.
SELECT CAST(t.created_at AS DATE) AS day,
       COUNT(DISTINCT CASE WHEN t.type = 'DAILY' THEN t.user_id END) AS daily_users,
       COUNT(DISTINCT CASE WHEN t.type = 'CHEST' THEN t.user_id END) AS chest_users,
       COUNT(DISTINCT CASE WHEN t.type = 'QUEST' THEN t.user_id END) AS quest_users,
       COUNT(DISTINCT CASE WHEN t.type IN ('QUEST','DAILY','CHEST','AD_REWARD') THEN t.user_id END) AS any_users,
       SUM(CASE WHEN t.type = 'DAILY' THEN t.amount ELSE 0 END) AS daily_exc,
       SUM(CASE WHEN t.type = 'CHEST' THEN t.amount ELSE 0 END) AS chest_exc,
       SUM(CASE WHEN t.type = 'QUEST' THEN t.amount ELSE 0 END) AS quest_exc
FROM exc_transactions t
WHERE t.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) AND t.amount > 0
GROUP BY CAST(t.created_at AS DATE)
ORDER BY day;

-- 2) Пересечение за последние 14 дней (по игрокам-дням): из тех, кто забрал ежедневный бонус, сколько в тот же день выполнили квест; из выполнивших квест - сколько открыли сундук.
SELECT COUNT(*) AS user_days,
       SUM(has_daily) AS with_daily,
       SUM(CASE WHEN has_daily = 1 AND has_quest = 1 THEN 1 ELSE 0 END) AS daily_and_quest,
       ROUND(100.0 * SUM(CASE WHEN has_daily = 1 AND has_quest = 1 THEN 1 ELSE 0 END) / NULLIF(SUM(has_daily), 0), 0) AS pct_daily_did_quest,
       SUM(has_quest) AS with_quest,
       SUM(CASE WHEN has_quest = 1 AND has_chest = 1 THEN 1 ELSE 0 END) AS quest_and_chest,
       ROUND(100.0 * SUM(CASE WHEN has_quest = 1 AND has_chest = 1 THEN 1 ELSE 0 END) / NULLIF(SUM(has_quest), 0), 0) AS pct_quest_opened_chest,
       SUM(has_chest) AS with_chest,
       ROUND(100.0 * SUM(CASE WHEN has_chest = 1 AND has_daily = 1 THEN 1 ELSE 0 END) / NULLIF(SUM(has_chest), 0), 0) AS pct_chest_also_daily
FROM (
    SELECT t.user_id, CAST(t.created_at AS DATE) AS d,
           MAX(CASE WHEN t.type = 'DAILY' THEN 1 ELSE 0 END) AS has_daily,
           MAX(CASE WHEN t.type = 'CHEST' THEN 1 ELSE 0 END) AS has_chest,
           MAX(CASE WHEN t.type = 'QUEST' THEN 1 ELSE 0 END) AS has_quest
    FROM exc_transactions t
    WHERE t.created_at >= DATEADD('DAY', -14, CURRENT_TIMESTAMP) AND t.amount > 0 AND t.type IN ('QUEST','DAILY','CHEST')
    GROUP BY t.user_id, CAST(t.created_at AS DATE)
) x;

-- 3) Сколько квестов за день делает игрок (в дни, когда выполнил хотя бы один), последние 14 дней: от этого зависит, сколько заданий «выполни N квестов» реалистично просить.
SELECT CASE WHEN cnt >= 4 THEN '4+' ELSE CAST(cnt AS VARCHAR) END AS quests_per_day, COUNT(*) AS user_days
FROM (
    SELECT s.user_id, CAST(s.updated_at AS DATE) AS d, COUNT(*) AS cnt
    FROM quest_submissions s
    WHERE s.status = 'APPROVED' AND s.updated_at >= DATEADD('DAY', -14, CURRENT_TIMESTAMP)
    GROUP BY s.user_id, CAST(s.updated_at AS DATE)
) q
GROUP BY CASE WHEN cnt >= 4 THEN '4+' ELSE CAST(cnt AS VARCHAR) END
ORDER BY quests_per_day;

-- 4) Привычка: у скольких игроков за 30 дней 1 / 2-3 / 4-7 / 8-15 / 16+ дней с любым действием (квест, бонус, сундук, реклама). Показывает, есть ли вообще ядро ежедневных.
SELECT CASE WHEN days = 1 THEN 'a: 1 день' WHEN days <= 3 THEN 'b: 2-3' WHEN days <= 7 THEN 'c: 4-7'
            WHEN days <= 15 THEN 'd: 8-15' ELSE 'e: 16+' END AS active_days,
       COUNT(*) AS players
FROM (
    SELECT t.user_id, COUNT(DISTINCT CAST(t.created_at AS DATE)) AS days
    FROM exc_transactions t
    WHERE t.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) AND t.amount > 0 AND t.type IN ('QUEST','DAILY','CHEST','AD_REWARD')
    GROUP BY t.user_id
) a
GROUP BY CASE WHEN days = 1 THEN 'a: 1 день' WHEN days <= 3 THEN 'b: 2-3' WHEN days <= 7 THEN 'c: 4-7'
              WHEN days <= 15 THEN 'd: 8-15' ELSE 'e: 16+' END
ORDER BY active_days;

-- 5) Цена сегодняшнего цикла для фонда за 30 дней: сколько EXC выдали сундук, ежедневный бонус и квесты, доля сундука+бонуса и среднее за одно открытие.
SELECT SUM(CASE WHEN t.type = 'CHEST' THEN t.amount ELSE 0 END) AS chest_exc,
       COUNT(CASE WHEN t.type = 'CHEST' THEN 1 END) AS chest_opens,
       ROUND(1.0 * SUM(CASE WHEN t.type = 'CHEST' THEN t.amount ELSE 0 END) / NULLIF(COUNT(CASE WHEN t.type = 'CHEST' THEN 1 END), 0), 0) AS avg_chest_exc,
       SUM(CASE WHEN t.type = 'DAILY' THEN t.amount ELSE 0 END) AS daily_exc,
       COUNT(CASE WHEN t.type = 'DAILY' THEN 1 END) AS daily_claims,
       SUM(CASE WHEN t.type = 'QUEST' THEN t.amount ELSE 0 END) AS quest_exc,
       ROUND(100.0 * SUM(CASE WHEN t.type IN ('CHEST','DAILY') THEN t.amount ELSE 0 END) / NULLIF(SUM(CASE WHEN t.type IN ('CHEST','DAILY','QUEST') THEN t.amount ELSE 0 END), 0), 1) AS pct_chest_daily_of_total
FROM exc_transactions t
WHERE t.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) AND t.amount > 0;

-- 6) Серии: распределение текущей серии среди игроков, заходивших за последние 14 дней (есть ли «ядро» с серией 7+).
SELECT CASE WHEN u.streak_days = 0 THEN 'a: 0' WHEN u.streak_days <= 2 THEN 'b: 1-2' WHEN u.streak_days <= 6 THEN 'c: 3-6'
            WHEN u.streak_days <= 13 THEN 'd: 7-13' ELSE 'e: 14+' END AS streak,
       COUNT(*) AS players
FROM app_users u
WHERE u.last_activity_date >= DATEADD('DAY', -14, CURRENT_DATE)
GROUP BY CASE WHEN u.streak_days = 0 THEN 'a: 0' WHEN u.streak_days <= 2 THEN 'b: 1-2' WHEN u.streak_days <= 6 THEN 'c: 3-6'
              WHEN u.streak_days <= 13 THEN 'd: 7-13' ELSE 'e: 14+' END
ORDER BY streak;
