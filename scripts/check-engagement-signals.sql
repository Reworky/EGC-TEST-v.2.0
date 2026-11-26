-- Какие действия первой недели предсказывают возврат и спонсорские квесты. Постоянные данные только читаются (создаются лишь временные таблицы этого запуска).
-- Когорта: зарегистрированы 21-90 дней назад, не заблокированы, и уже работал журнал активности. Сигнал = действие в первые 7 суток после регистрации.
-- Исходы считаются ПОСЛЕ 7-х суток: back = был активен снова; sp_ok = одобрен спонсорский квест (банк, партнёр или подписка), взятый после 7-х суток;
-- hard_take = взял банковский/партнёрский квест (без подписок на каналы) после 7-х суток.
-- Для каждого сигнала берутся только игроки, зарегистрированные после первой записи этого сигнала (since): иначе у старых игроков сигнала «не было» просто потому, что он не записывался.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-engagement-signals.sql

CREATE LOCAL TEMPORARY TABLE coh AS
SELECT u.id AS uid, u.telegram_id AS tg, u.created_at AS reg_ts,
       CAST(DATEADD('HOUR', 3, u.created_at) AS DATE) AS reg_date,
       DATEADD('DAY', 7, u.created_at) AS end7,
       CASE WHEN COALESCE(u.traffic_source_code, '') <> '' THEN 1 ELSE 0 END AS paid
FROM app_users u
WHERE u.created_at >= DATEADD('DAY', -90, CURRENT_TIMESTAMP)
  AND u.created_at < DATEADD('DAY', -21, CURRENT_TIMESTAMP)
  AND CAST(DATEADD('HOUR', 3, u.created_at) AS DATE) >= (SELECT MIN(activity_day) FROM user_activity_days)
  AND COALESCE(u.blocked, FALSE) = FALSE;

CREATE LOCAL TEMPORARY TABLE outc AS
SELECT c.uid, c.paid, c.reg_ts,
  CASE WHEN EXISTS (SELECT 1 FROM user_activity_days a WHERE a.user_id = c.uid AND a.activity_day >= DATEADD('DAY', 7, c.reg_date)) THEN 1 ELSE 0 END AS back,
  CASE WHEN EXISTS (SELECT 1 FROM quest_submissions s JOIN quests q ON q.id = s.quest_id WHERE s.user_id = c.uid AND s.status = 'APPROVED' AND s.created_at >= c.end7
        AND (q.sponsored = TRUE OR q.external_auto_approve = TRUE OR q.channel_check_chat_id IS NOT NULL)) THEN 1 ELSE 0 END AS sp_ok,
  CASE WHEN EXISTS (SELECT 1 FROM quest_submissions s JOIN quests q ON q.id = s.quest_id WHERE s.user_id = c.uid AND s.created_at >= c.end7
        AND (q.sponsored = TRUE OR q.external_auto_approve = TRUE) AND q.channel_check_chat_id IS NULL) THEN 1 ELSE 0 END AS hard_take
FROM coh c;

-- Обычные (не спонсорские) квесты, одобренные за первые 7 суток, по игрокам
CREATE LOCAL TEMPORARY TABLE gq AS
SELECT c.uid, COUNT(*) AS cnt
FROM coh c JOIN quest_submissions s ON s.user_id = c.uid JOIN quests q ON q.id = s.quest_id
WHERE s.status = 'APPROVED' AND COALESCE(s.updated_at, s.created_at) < c.end7
  AND COALESCE(q.sponsored, FALSE) = FALSE AND COALESCE(q.external_auto_approve, FALSE) = FALSE AND q.channel_check_chat_id IS NULL
GROUP BY c.uid;

CREATE LOCAL TEMPORARY TABLE sig (sid INT, uid BIGINT);

-- 1-2: обычные квесты
INSERT INTO sig SELECT 1, uid FROM gq WHERE cnt >= 1;
INSERT INTO sig SELECT 2, uid FROM gq WHERE cnt >= 3;
-- 3: оплата деньгами (звёзды, VPN, рубли в магазине, донат)
INSERT INTO sig SELECT 3, c.uid FROM coh c JOIN stars_purchases p ON p.telegram_id = c.tg WHERE p.created_at < c.end7 AND p.stars_amount > 0;
INSERT INTO sig SELECT 3, c.uid FROM coh c JOIN vpn2_payments p ON p.telegram_id = c.tg WHERE p.created_at < c.end7 AND (p.stars_amount > 0 OR p.rub_amount > 0) AND p.refunded_at IS NULL;
INSERT INTO sig SELECT 3, c.uid FROM coh c JOIN shop_ruble_orders p ON p.telegram_id = c.tg WHERE p.created_at < c.end7 AND p.status = 'APPROVED';
INSERT INTO sig SELECT 3, c.uid FROM coh c JOIN gem_purchase_requests p ON p.user_id = c.uid WHERE p.created_at < c.end7 AND p.status = 'APPROVED' AND (p.stars_amount > 0 OR p.rub_paid > 0);
-- 4: пригласил друга, который выполнил хотя бы 1 квест
INSERT INTO sig SELECT 4, c.uid FROM coh c JOIN app_users f ON f.referred_by_telegram_id = c.tg WHERE f.created_at < c.end7 AND f.completed_quests >= 1;
-- 5-6: активность в 2 и в 3 разных дня из первых семи
INSERT INTO sig SELECT 5, c.uid FROM coh c JOIN user_activity_days a ON a.user_id = c.uid WHERE a.activity_day < DATEADD('DAY', 7, c.reg_date) GROUP BY c.uid HAVING COUNT(DISTINCT a.activity_day) >= 2;
INSERT INTO sig SELECT 6, c.uid FROM coh c JOIN user_activity_days a ON a.user_id = c.uid WHERE a.activity_day < DATEADD('DAY', 7, c.reg_date) GROUP BY c.uid HAVING COUNT(DISTINCT a.activity_day) >= 3;
-- 7-8: код дня хотя бы раз и в 3 разных дня
INSERT INTO sig SELECT 7, c.uid FROM coh c JOIN daily_code_claims d ON d.user_id = c.uid WHERE d.claimed_at < c.end7 GROUP BY c.uid;
INSERT INTO sig SELECT 8, c.uid FROM coh c JOIN daily_code_claims d ON d.user_id = c.uid WHERE d.claimed_at < c.end7 GROUP BY c.uid HAVING COUNT(DISTINCT d.claim_date) >= 3;
-- 9: недельная цель
INSERT INTO sig SELECT 9, c.uid FROM coh c JOIN weekly_goal_claims w ON w.user_id = c.uid WHERE w.claimed_at < c.end7;
-- 10: турнир
INSERT INTO sig SELECT 10, c.uid FROM coh c JOIN tournament_entries t ON t.user_id = c.uid WHERE t.created_at < c.end7;
-- 11: обмен в магазине или заявка на вывод
INSERT INTO sig SELECT 11, c.uid FROM coh c JOIN reward_requests r ON r.user_id = c.uid WHERE r.created_at < c.end7;
-- 12: привязал игровой тег или аккаунт
INSERT INTO sig SELECT 12, c.uid FROM coh c JOIN app_users u ON u.id = c.uid
  WHERE u.brawl_tag_confirmed_at < c.end7 OR u.clash_tag_confirmed_at < c.end7 OR u.clash_royale_tag_confirmed_at < c.end7
     OR u.dota_linked_at < c.end7 OR u.cs2linked_at < c.end7 OR u.pubg_linked_at < c.end7;
-- 13: ежедневный бонус
INSERT INTO sig SELECT 13, c.uid FROM coh c JOIN exc_transactions e ON e.user_id = c.uid WHERE e.type = 'DAILY' AND e.created_at < c.end7;
-- 14: колесо за EXC
INSERT INTO sig SELECT 14, c.uid FROM coh c JOIN exc_wheel_spins w ON w.user_id = c.uid WHERE w.created_at < c.end7;
-- 15: задание «Соцсети»
INSERT INTO sig SELECT 15, c.uid FROM coh c JOIN social_task_claims s ON s.user_id = c.uid WHERE s.created_at < c.end7;
-- 16-17: опрос, отзыв о боте
INSERT INTO sig SELECT 16, c.uid FROM coh c JOIN poll_votes p ON p.user_id = c.uid WHERE p.created_at < c.end7;
INSERT INTO sig SELECT 17, c.uid FROM coh c JOIN bot_reviews b ON b.user_id = c.uid WHERE b.created_at < c.end7;

CREATE LOCAL TEMPORARY TABLE nm (sid INT, title VARCHAR(100), since_ts TIMESTAMP);
INSERT INTO nm SELECT 1,  '1+ обычный квест',            TIMESTAMP '2000-01-01 00:00:00';
INSERT INTO nm SELECT 2,  '3+ обычных квеста',           TIMESTAMP '2000-01-01 00:00:00';
INSERT INTO nm SELECT 3,  'Оплата деньгами',             TIMESTAMP '2000-01-01 00:00:00';
INSERT INTO nm SELECT 4,  'Позвал друга с квестом',      TIMESTAMP '2000-01-01 00:00:00';
INSERT INTO nm SELECT 5,  '2+ дня активности',           TIMESTAMP '2000-01-01 00:00:00';
INSERT INTO nm SELECT 6,  '3+ дня активности',           TIMESTAMP '2000-01-01 00:00:00';
INSERT INTO nm SELECT 7,  'Код дня хотя бы раз',         (SELECT MIN(claimed_at) FROM daily_code_claims);
INSERT INTO nm SELECT 8,  'Код дня в 3 разных дня',      (SELECT MIN(claimed_at) FROM daily_code_claims);
INSERT INTO nm SELECT 9,  'Недельная цель',              (SELECT MIN(claimed_at) FROM weekly_goal_claims);
INSERT INTO nm SELECT 10, 'Участие в турнире',           (SELECT MIN(created_at) FROM tournament_entries);
INSERT INTO nm SELECT 11, 'Обмен или заявка на вывод',   TIMESTAMP '2000-01-01 00:00:00';
INSERT INTO nm SELECT 12, 'Привязал игровой тег',        TIMESTAMP '2000-01-01 00:00:00';
INSERT INTO nm SELECT 13, 'Ежедневный бонус',            (SELECT MIN(created_at) FROM exc_transactions WHERE type = 'DAILY');
INSERT INTO nm SELECT 14, 'Колесо за EXC',               (SELECT MIN(created_at) FROM exc_wheel_spins);
INSERT INTO nm SELECT 15, 'Задание Соцсети',             (SELECT MIN(created_at) FROM social_task_claims);
INSERT INTO nm SELECT 16, 'Голос в опросе',              (SELECT MIN(created_at) FROM poll_votes);
INSERT INTO nm SELECT 17, 'Отзыв о боте',                (SELECT MIN(created_at) FROM bot_reviews);

-- 1) Размер когорты, база для сравнения и с какой даты пишется каждый сигнал
SELECT COUNT(*) AS cohort, SUM(paid) AS with_source, MIN(reg_ts) AS reg_from, MAX(reg_ts) AS reg_to,
       ROUND(100.0 * SUM(back) / NULLIF(COUNT(*), 0), 1) AS back_pct,
       SUM(sp_ok) AS sp_ok_n, SUM(hard_take) AS hard_take_n
FROM outc;
SELECT sid, title, since_ts FROM nm ORDER BY sid;

-- 2) Все игроки когорты: у кого сигнал был (_yes) и у кого не было (_no)
SELECT n.sid, n.title,
       COUNT(*) AS base,
       SUM(CASE WHEN x.uid IS NOT NULL THEN 1 ELSE 0 END) AS had,
       ROUND(100.0 * SUM(CASE WHEN x.uid IS NOT NULL THEN o.back ELSE 0 END) / NULLIF(SUM(CASE WHEN x.uid IS NOT NULL THEN 1 ELSE 0 END), 0), 1) AS back_yes,
       ROUND(100.0 * SUM(CASE WHEN x.uid IS NULL THEN o.back ELSE 0 END) / NULLIF(SUM(CASE WHEN x.uid IS NULL THEN 1 ELSE 0 END), 0), 1) AS back_no,
       SUM(CASE WHEN x.uid IS NOT NULL THEN o.sp_ok ELSE 0 END) AS sp_n_yes,
       ROUND(100.0 * SUM(CASE WHEN x.uid IS NOT NULL THEN o.sp_ok ELSE 0 END) / NULLIF(SUM(CASE WHEN x.uid IS NOT NULL THEN 1 ELSE 0 END), 0), 1) AS sp_yes,
       ROUND(100.0 * SUM(CASE WHEN x.uid IS NULL THEN o.sp_ok ELSE 0 END) / NULLIF(SUM(CASE WHEN x.uid IS NULL THEN 1 ELSE 0 END), 0), 1) AS sp_no,
       SUM(CASE WHEN x.uid IS NOT NULL THEN o.hard_take ELSE 0 END) AS hard_n_yes,
       ROUND(100.0 * SUM(CASE WHEN x.uid IS NOT NULL THEN o.hard_take ELSE 0 END) / NULLIF(SUM(CASE WHEN x.uid IS NOT NULL THEN 1 ELSE 0 END), 0), 1) AS hard_yes,
       ROUND(100.0 * SUM(CASE WHEN x.uid IS NULL THEN o.hard_take ELSE 0 END) / NULLIF(SUM(CASE WHEN x.uid IS NULL THEN 1 ELSE 0 END), 0), 1) AS hard_no
FROM nm n
JOIN outc o ON o.reg_ts >= n.since_ts
LEFT JOIN (SELECT DISTINCT sid, uid FROM sig) x ON x.sid = n.sid AND x.uid = o.uid
GROUP BY n.sid, n.title
ORDER BY n.sid;

-- 3) То же только для игроков с меткой источника (то, что важно при оценке закупов)
SELECT n.sid, n.title,
       COUNT(*) AS base,
       SUM(CASE WHEN x.uid IS NOT NULL THEN 1 ELSE 0 END) AS had,
       ROUND(100.0 * SUM(CASE WHEN x.uid IS NOT NULL THEN o.back ELSE 0 END) / NULLIF(SUM(CASE WHEN x.uid IS NOT NULL THEN 1 ELSE 0 END), 0), 1) AS back_yes,
       ROUND(100.0 * SUM(CASE WHEN x.uid IS NULL THEN o.back ELSE 0 END) / NULLIF(SUM(CASE WHEN x.uid IS NULL THEN 1 ELSE 0 END), 0), 1) AS back_no,
       SUM(CASE WHEN x.uid IS NOT NULL THEN o.sp_ok ELSE 0 END) AS sp_n_yes,
       ROUND(100.0 * SUM(CASE WHEN x.uid IS NOT NULL THEN o.sp_ok ELSE 0 END) / NULLIF(SUM(CASE WHEN x.uid IS NOT NULL THEN 1 ELSE 0 END), 0), 1) AS sp_yes,
       ROUND(100.0 * SUM(CASE WHEN x.uid IS NULL THEN o.sp_ok ELSE 0 END) / NULLIF(SUM(CASE WHEN x.uid IS NULL THEN 1 ELSE 0 END), 0), 1) AS sp_no,
       SUM(CASE WHEN x.uid IS NOT NULL THEN o.hard_take ELSE 0 END) AS hard_n_yes,
       ROUND(100.0 * SUM(CASE WHEN x.uid IS NOT NULL THEN o.hard_take ELSE 0 END) / NULLIF(SUM(CASE WHEN x.uid IS NOT NULL THEN 1 ELSE 0 END), 0), 1) AS hard_yes,
       ROUND(100.0 * SUM(CASE WHEN x.uid IS NULL THEN o.hard_take ELSE 0 END) / NULLIF(SUM(CASE WHEN x.uid IS NULL THEN 1 ELSE 0 END), 0), 1) AS hard_no
FROM nm n
JOIN outc o ON o.reg_ts >= n.since_ts AND o.paid = 1
LEFT JOIN (SELECT DISTINCT sid, uid FROM sig) x ON x.sid = n.sid AND x.uid = o.uid
GROUP BY n.sid, n.title
ORDER BY n.sid;
