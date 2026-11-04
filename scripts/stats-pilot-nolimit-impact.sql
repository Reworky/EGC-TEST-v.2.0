-- Влияние пилота «квесты без стен» (Brawl Stars, без кулдауна, награда по кривой убывания) - стоит ли вводить для всех. Только чтение.
-- Окна: до пилота 01-14.09.2026, пилот 15.09-06.10.2026 (квест выключен 07.10), после - с 07.10 до сегодня. Время сервера.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-pilot-nolimit-impact.sql

-- 1) Цена «снятия стен»: сколько прохождений и EXC пришлось на первое за день (оно было бы и с кулдауном) и на второе и дальше (появилось только из-за отмены кулдауна).
SELECT CASE WHEN x.n = 1 THEN '1) первое за день' ELSE '2) второе и далее (дала отмена кулдауна)' END AS kind,
       COUNT(*) AS completions, COUNT(DISTINCT x.user_id) AS players, SUM(x.coins) AS exc, ROUND(AVG(x.coins), 0) AS avg_exc
FROM (
    SELECT s.user_id, COALESCE(s.awarded_coins, 0) AS coins,
           ROW_NUMBER() OVER (PARTITION BY s.user_id, CAST(s.updated_at AS DATE) ORDER BY s.updated_at) AS n
    FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
    WHERE q.repeatable_no_cooldown_eligible = TRUE AND s.status = 'APPROVED'
      AND s.updated_at >= DATE '2026-09-15' AND s.updated_at < DATE '2026-10-07'
) x
GROUP BY CASE WHEN x.n = 1 THEN '1) первое за день' ELSE '2) второе и далее (дала отмена кулдауна)' END
ORDER BY kind;

-- 2) Выросла ли активность по Brawl Stars в целом (а не просто перетекла в пилотный квест): в среднем за день по периодам.
--    approved - все одобренные квесты игры, pilot - из них пилотный.
SELECT d.period, COUNT(*) AS days_with_activity,
       ROUND(AVG(d.players), 1) AS avg_players_per_day, ROUND(AVG(d.approved), 1) AS avg_approved_per_day,
       ROUND(AVG(d.pilot_approved), 1) AS avg_pilot_per_day, ROUND(AVG(d.approved - d.pilot_approved), 1) AS avg_other_per_day
FROM (
    SELECT CASE WHEN CAST(s.updated_at AS DATE) < DATE '2026-09-15' THEN '1) до пилота (01-14.09)'
                WHEN CAST(s.updated_at AS DATE) < DATE '2026-10-07' THEN '2) пилот (15.09-06.10)'
                ELSE '3) после (с 07.10)' END AS period,
           CAST(s.updated_at AS DATE) AS dt,
           COUNT(DISTINCT s.user_id) AS players, COUNT(*) AS approved,
           SUM(CASE WHEN q.repeatable_no_cooldown_eligible = TRUE THEN 1 ELSE 0 END) AS pilot_approved
    FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
    WHERE s.status = 'APPROVED'
      AND q.game_name = (SELECT MIN(game_name) FROM quests WHERE repeatable_no_cooldown_eligible = TRUE)
      AND s.updated_at >= DATE '2026-09-01' AND s.updated_at < CURRENT_DATE
    GROUP BY CASE WHEN CAST(s.updated_at AS DATE) < DATE '2026-09-15' THEN '1) до пилота (01-14.09)'
                  WHEN CAST(s.updated_at AS DATE) < DATE '2026-10-07' THEN '2) пилот (15.09-06.10)'
                  ELSE '3) после (с 07.10)' END,
             CAST(s.updated_at AS DATE)
) d
GROUP BY d.period
ORDER BY d.period;

-- 3) Пилот против контроля: пилот - кто прошёл пилотный квест 15.09-06.10; контроль - прошёл другой квест Brawl Stars в те же даты, но не пилотный.
--    Сравниваем активные дни (дни с любым начислением за квест/ежедневку/сундук/рекламу) ДО пилота и за последние 14 дней, EXC за 30 дней и выводы за 90 дней.
WITH pilot AS (
    SELECT DISTINCT s.user_id FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
    WHERE q.repeatable_no_cooldown_eligible = TRUE AND s.status = 'APPROVED'
      AND s.updated_at >= DATE '2026-09-15' AND s.updated_at < DATE '2026-10-07'
), ctrl AS (
    SELECT DISTINCT s.user_id FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
    WHERE q.repeatable_no_cooldown_eligible = FALSE AND s.status = 'APPROVED'
      AND q.game_name = (SELECT MIN(game_name) FROM quests WHERE repeatable_no_cooldown_eligible = TRUE)
      AND s.updated_at >= DATE '2026-09-15' AND s.updated_at < DATE '2026-10-07'
      AND s.user_id NOT IN (SELECT user_id FROM pilot)
), grp AS (
    SELECT user_id, '1) пилот' AS g FROM pilot
    UNION ALL
    SELECT user_id, '2) контроль' AS g FROM ctrl
), act AS (
    SELECT t.user_id,
           COUNT(DISTINCT CASE WHEN t.created_at >= DATE '2026-09-01' AND t.created_at < DATE '2026-09-15' THEN CAST(t.created_at AS DATE) END) AS d_before,
           COUNT(DISTINCT CASE WHEN t.created_at >= DATEADD('DAY', -14, CURRENT_TIMESTAMP) THEN CAST(t.created_at AS DATE) END) AS d_last14,
           SUM(CASE WHEN t.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) THEN t.amount ELSE 0 END) AS exc_30d
    FROM exc_transactions t
    WHERE t.amount > 0 AND t.type IN ('QUEST', 'DAILY', 'CHEST', 'AD_REWARD')
    GROUP BY t.user_id
), wd AS (
    SELECT rr.user_id, COUNT(*) AS wd_cnt
    FROM reward_requests rr JOIN reward_items ri ON ri.id = rr.reward_id
    WHERE ri.category = 'Вывод' AND rr.status = 'APPROVED' AND rr.paid_at IS NOT NULL
      AND rr.paid_at >= DATEADD('DAY', -90, CURRENT_TIMESTAMP)
    GROUP BY rr.user_id
)
SELECT g.g AS grp, COUNT(*) AS players,
       ROUND(AVG(COALESCE(a.d_before, 0)), 1) AS avg_active_days_before,
       ROUND(AVG(COALESCE(a.d_last14, 0)), 1) AS avg_active_days_last14,
       ROUND(100.0 * SUM(CASE WHEN COALESCE(a.d_last14, 0) > 0 THEN 1 ELSE 0 END) / COUNT(*), 0) AS pct_active_last14,
       ROUND(AVG(COALESCE(a.exc_30d, 0)), 0) AS avg_exc_30d,
       ROUND(100.0 * SUM(CASE WHEN COALESCE(w.wd_cnt, 0) > 0 THEN 1 ELSE 0 END) / COUNT(*), 0) AS pct_withdrew_90d
FROM grp g
LEFT JOIN act a ON a.user_id = g.user_id
LEFT JOIN wd w ON w.user_id = g.user_id
GROUP BY g.g
ORDER BY g.g;

-- 4) Качество пилота: сколько игроков и EXC пришлось на помеченных как «подозрение на фрод» или заблокированных.
SELECT CASE WHEN u.blocked = TRUE THEN '3) заблокирован' WHEN u.fraud_suspect = TRUE THEN '2) подозрение на фрод' ELSE '1) обычный' END AS status,
       COUNT(DISTINCT u.id) AS players, COUNT(*) AS completions, SUM(COALESCE(s.awarded_coins, 0)) AS exc
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id JOIN app_users u ON u.id = s.user_id
WHERE q.repeatable_no_cooldown_eligible = TRUE AND s.status = 'APPROVED'
  AND s.updated_at >= DATE '2026-09-15' AND s.updated_at < DATE '2026-10-07'
GROUP BY CASE WHEN u.blocked = TRUE THEN '3) заблокирован' WHEN u.fraud_suspect = TRUE THEN '2) подозрение на фрод' ELSE '1) обычный' END
ORDER BY status;

-- 5) Доля пилота в эмиссии за квесты по неделям: сколько EXC всего начислено за квесты и сколько из них пилотный квест.
SELECT w.week, w.quest_exc_total, COALESCE(p.pilot_exc, 0) AS pilot_exc,
       ROUND(100.0 * COALESCE(p.pilot_exc, 0) / NULLIF(w.quest_exc_total, 0), 1) AS pilot_pct
FROM (
    SELECT FORMATDATETIME(t.created_at, 'YYYY-ww') AS week, SUM(t.amount) AS quest_exc_total
    FROM exc_transactions t
    WHERE t.type = 'QUEST' AND t.amount > 0 AND t.created_at >= DATE '2026-09-01'
    GROUP BY FORMATDATETIME(t.created_at, 'YYYY-ww')
) w
LEFT JOIN (
    SELECT FORMATDATETIME(s.updated_at, 'YYYY-ww') AS week, SUM(COALESCE(s.awarded_coins, 0)) AS pilot_exc
    FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
    WHERE q.repeatable_no_cooldown_eligible = TRUE AND s.status = 'APPROVED'
    GROUP BY FORMATDATETIME(s.updated_at, 'YYYY-ww')
) p ON p.week = w.week
ORDER BY w.week;

-- 6) Где отмена кулдауна вообще могла бы сработать: квесты с автопроверкой за 30 дней - сколько игроков, прохождений и сколько игроков ходят 5+ дней
--    (те, у кого кулдаун мешает чаще всего).
SELECT t.quest_id, t.title, t.game_name, COUNT(*) AS players_30d, SUM(t.cnt) AS approved_30d,
       SUM(CASE WHEN t.days_cnt >= 5 THEN 1 ELSE 0 END) AS players_5plus_days, MAX(t.days_cnt) AS max_days
FROM (
    SELECT q.id AS quest_id, q.title, q.game_name, s.user_id, COUNT(*) AS cnt, COUNT(DISTINCT CAST(s.updated_at AS DATE)) AS days_cnt
    FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
    WHERE s.status = 'APPROVED' AND s.updated_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)
      AND (q.brawl_verify_type IS NOT NULL OR q.clash_royale_verify_type IS NOT NULL OR q.clash_verify_type IS NOT NULL OR q.cs2verify_type IS NOT NULL)
    GROUP BY q.id, q.title, q.game_name, s.user_id
) t
GROUP BY t.quest_id, t.title, t.game_name
ORDER BY approved_30d DESC
LIMIT 25;
