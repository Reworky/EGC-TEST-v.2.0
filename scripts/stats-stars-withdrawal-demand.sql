-- Сколько активных игроков смогут и захотят вывести звёзды, если цены звёздных позиций умножить на коэффициент. Только читает базу.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-stars-withdrawal-demand.sql
-- Параметры: курс фонда (Health Ratio) и множитель - поменяйте два числа в первой строке ниже (сейчас курс 0.9, множитель 1.25).
CREATE LOCAL TEMPORARY TABLE params AS SELECT CAST(0.9 AS DOUBLE) AS ratio, CAST(1.25 AS DOUBLE) AS mult;

-- Игроки: активные, их баланс, остаток лимита вывода (по рангу + Pass + разовый партнёрский запас), есть ли одобренный партнёрский квест ОТП.
CREATE LOCAL TEMPORARY TABLE pl AS
SELECT u.id, u.coins,
  GREATEST(0, (CASE WHEN u.xp >= 75000 THEN 150000 WHEN u.xp >= 35000 THEN 100000 WHEN u.xp >= 15000 THEN 80000 WHEN u.xp >= 5000 THEN 50000 WHEN u.xp >= 1000 THEN 25000 ELSE 10000 END)
    + (CASE WHEN u.egc_pass_active_until IS NOT NULL AND u.egc_pass_active_until > CURRENT_TIMESTAMP THEN 10000 ELSE 0 END)
    - (CASE WHEN u.withdrawal_year = YEAR(CURRENT_DATE) AND u.withdrawal_month = MONTH(CURRENT_DATE) THEN u.monthly_withdrawn_exc ELSE 0 END))
    + GREATEST(0, u.partner_withdrawal_allowance_exc) AS limit_left,
  CASE WHEN EXISTS (SELECT 1 FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
                    WHERE s.user_id = u.id AND q.game_name = 'ОТП Банк' AND s.status = 'APPROVED') THEN 1 ELSE 0 END AS has_partner,
  CASE WHEN u.egc_pass_active_until IS NOT NULL AND u.egc_pass_active_until > CURRENT_TIMESTAMP THEN 1 ELSE 0 END AS has_pass,
  GREATEST(COALESCE(u.last_activity_date, DATE '2000-01-01'),
           CAST(COALESCE(u.last_bot_activity_at, TIMESTAMP '2000-01-01 00:00:00') AS DATE),
           CAST(COALESCE(u.last_mini_app_open_at, TIMESTAMP '2000-01-01 00:00:00') AS DATE)) AS last_act
FROM app_users u
WHERE u.registration_completed = TRUE AND COALESCE(u.blocked, FALSE) = FALSE AND COALESCE(u.withdrawal_frozen, FALSE) = FALSE;

CREATE LOCAL TEMPORARY TABLE act AS SELECT * FROM pl WHERE last_act >= DATEADD('DAY', -30, CURRENT_DATE);

-- 0) цены позиций при заданных курсе и множителе
SELECT 'цена позиции' AS part, t.stars AS stars, ROUND(ROUND(t.base / p.ratio) * p.mult / 100) * 100 AS price_exc
FROM params p CROSS JOIN (SELECT 75 AS stars, 11800 AS base UNION ALL SELECT 100, 15600 UNION ALL SELECT 150, 23300 UNION ALL SELECT 250, 38900
  UNION ALL SELECT 350, 54900 UNION ALL SELECT 500, 77900) t ORDER BY t.stars;

-- 1) активные за 30 дней по каждой позиции: хватает баланса / хватает ещё и лимита / плюс есть партнёрский квест (иначе порог 10 000 EXC блокирует звёзды)
SELECT t.stars,
  ROUND(ROUND(t.base / p.ratio) * p.mult / 100) * 100 AS price_exc,
  SUM(CASE WHEN a.coins >= ROUND(ROUND(t.base / p.ratio) * p.mult / 100) * 100 THEN 1 ELSE 0 END) AS enough_balance,
  SUM(CASE WHEN a.coins >= ROUND(ROUND(t.base / p.ratio) * p.mult / 100) * 100 AND a.limit_left >= ROUND(ROUND(t.base / p.ratio) * p.mult / 100) * 100 THEN 1 ELSE 0 END) AS and_limit,
  SUM(CASE WHEN a.coins >= ROUND(ROUND(t.base / p.ratio) * p.mult / 100) * 100 AND a.limit_left >= ROUND(ROUND(t.base / p.ratio) * p.mult / 100) * 100 AND a.has_partner = 1 THEN 1 ELSE 0 END) AS and_partner_quest
FROM params p CROSS JOIN act a CROSS JOIN (SELECT 75 AS stars, 11800 AS base UNION ALL SELECT 100, 15600 UNION ALL SELECT 150, 23300 UNION ALL SELECT 250, 38900
  UNION ALL SELECT 350, 54900 UNION ALL SELECT 500, 77900) t
GROUP BY t.stars, t.base, p.ratio, p.mult ORDER BY t.stars;

-- 2) итого уникальных игроков, которые могут вывести хотя бы 75 ⭐ (самую дешёвую позицию)
SELECT 'активных за 30 дн.' AS metric, COUNT(*) AS n FROM act
UNION ALL SELECT 'хватает баланса на 75 ⭐', COUNT(*) FROM act, params p WHERE act.coins >= ROUND(ROUND(11800 / p.ratio) * p.mult / 100) * 100
UNION ALL SELECT '+ хватает лимита вывода', COUNT(*) FROM act, params p WHERE act.coins >= ROUND(ROUND(11800 / p.ratio) * p.mult / 100) * 100 AND act.limit_left >= ROUND(ROUND(11800 / p.ratio) * p.mult / 100) * 100
UNION ALL SELECT '+ одобренный квест ОТП (реально смогут сейчас)', COUNT(*) FROM act, params p WHERE act.coins >= ROUND(ROUND(11800 / p.ratio) * p.mult / 100) * 100 AND act.limit_left >= ROUND(ROUND(11800 / p.ratio) * p.mult / 100) * 100 AND act.has_partner = 1
UNION ALL SELECT 'из них с EGC Pass', COUNT(*) FROM act, params p WHERE act.coins >= ROUND(ROUND(11800 / p.ratio) * p.mult / 100) * 100 AND act.limit_left >= ROUND(ROUND(11800 / p.ratio) * p.mult / 100) * 100 AND act.has_partner = 1 AND act.has_pass = 1;
