-- Мультиаккаунты и лимит вывода: создают ли их ради обхода месячного лимита или ради бонусов (только чтение, временные таблицы живут в сессии и в базу не пишутся).
-- Запуск НА СЕРВЕРЕ: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/audit-multiaccount-vs-limits.sql > /root/audit-multi.txt 2>&1
-- Ключ реквизитов тот же, что в RewardService.destinationKey: кошелёк TON/USDT (w:), @юзернейм получателя звёзд (u:), телефон/карта - последние 10 цифр (d:).
-- «Кластер» = один и тот же реквизит у 2+ аккаунтов. Лимит игрока считается по ТЕКУЩЕМУ XP (приближение: ступени 10k/25k/50k/80k/100k/150k).
-- Гипотеза владельца: мультики нужны, чтобы вывести больше лимита. Если она верна, то (Q3) участники кластеров упираются в лимит чаще остальных,
-- (Q4) суммарный вывод кластера за месяц превышает лимит одного аккаунта, а (Q5) их заработок идёт с квестов, а не с бонусов.

CREATE LOCAL TEMPORARY TABLE tmp_wd AS
SELECT s.req, s.user_id, s.status, s.exc, s.created_at,
       CASE
         WHEN UPPER(s.d) LIKE 'TON:%' OR UPPER(s.d) LIKE 'USDT%'
           THEN 'w:' || LOWER(TRIM(REGEXP_REPLACE(REGEXP_REPLACE(s.d, '^[^:]*:', ''), ':rubles=.*$', '')))
         WHEN REGEXP_LIKE(TRIM(s.d), '^@?[A-Za-z0-9_]{5,32}$')
           THEN 'u:' || LOWER(REPLACE(TRIM(s.d), '@', ''))
         WHEN LENGTH(REGEXP_REPLACE(s.d, '[^0-9]', '')) >= 10
           THEN 'd:' || RIGHT(REGEXP_REPLACE(s.d, '[^0-9]', ''), 10)
         ELSE NULL
       END AS dest
FROM (SELECT rr.id AS req, rr.user_id, rr.status, COALESCE(rr.paid_price_coins, ri.price_coins, 0) AS exc, rr.created_at,
             REGEXP_REPLACE(CASE WHEN LOCATE('Реквизит:', rr.payout_details) > 0
                                 THEN SUBSTRING(rr.payout_details, LOCATE('Реквизит:', rr.payout_details) + 9)
                                 ELSE rr.payout_details END, ':rubles=.*$', '') AS d
      FROM reward_requests rr JOIN reward_items ri ON ri.id = rr.reward_id
      WHERE ri.category = 'Вывод' AND rr.status IN ('PENDING', 'IN_PROGRESS', 'APPROVED') AND rr.payout_details IS NOT NULL) s;

CREATE LOCAL TEMPORARY TABLE tmp_cluster AS
SELECT dest, COUNT(DISTINCT user_id) AS accounts, SUM(exc) AS total_exc, COUNT(*) AS requests
FROM tmp_wd WHERE dest IS NOT NULL GROUP BY dest HAVING COUNT(DISTINCT user_id) >= 2;

CREATE LOCAL TEMPORARY TABLE tmp_multi_user AS
SELECT DISTINCT w.user_id FROM tmp_wd w JOIN tmp_cluster c ON c.dest = w.dest;

CREATE LOCAL TEMPORARY TABLE tmp_user_month AS
SELECT w.user_id, YEAR(w.created_at) * 100 + MONTH(w.created_at) AS ym, SUM(w.exc) AS withdrawn,
       CASE WHEN u.xp >= 75000 THEN 150000 WHEN u.xp >= 35000 THEN 100000 WHEN u.xp >= 15000 THEN 80000
            WHEN u.xp >= 5000 THEN 50000 WHEN u.xp >= 1000 THEN 25000 ELSE 10000 END AS lim,
       CASE WHEN m.user_id IS NULL THEN 0 ELSE 1 END AS is_multi
FROM tmp_wd w JOIN app_users u ON u.id = w.user_id LEFT JOIN tmp_multi_user m ON m.user_id = w.user_id
GROUP BY w.user_id, YEAR(w.created_at) * 100 + MONTH(w.created_at), u.xp, m.user_id;

-- Q1) Масштаб: сколько аккаунтов выводило, сколько реквизитов делят 2+ аккаунта, какая доля выплат на них приходится
SELECT (SELECT COUNT(DISTINCT user_id) FROM tmp_wd) AS accounts_with_withdrawals,
       (SELECT COUNT(*) FROM tmp_cluster) AS shared_destinations,
       (SELECT COUNT(*) FROM tmp_multi_user) AS accounts_in_clusters,
       (SELECT SUM(exc) FROM tmp_wd) AS exc_total,
       (SELECT SUM(w.exc) FROM tmp_wd w JOIN tmp_cluster c ON c.dest = w.dest) AS exc_to_shared_destinations,
       ROUND(100.0 * (SELECT SUM(w.exc) FROM tmp_wd w JOIN tmp_cluster c ON c.dest = w.dest) / NULLIF((SELECT SUM(exc) FROM tmp_wd), 0), 1) AS pct_exc_to_shared;

-- Q2) Крупнейшие кластеры (реквизит скрыт хэшем префикса, ids - Telegram ID участников)
SELECT SUBSTRING(c.dest, 1, 2) AS kind, c.accounts, c.requests, c.total_exc,
       (SELECT LISTAGG(CAST(x.telegram_id AS VARCHAR), ',') FROM app_users x WHERE x.id IN (SELECT w.user_id FROM tmp_wd w WHERE w.dest = c.dest)) AS ids
FROM tmp_cluster c ORDER BY c.total_exc DESC LIMIT 30;

-- Q3) Упор в лимит: доля «аккаунт-месяцев» с выводом >= 90% лимита у участников кластеров и у остальных
SELECT CASE WHEN is_multi = 1 THEN 'в кластере' ELSE 'вне кластеров' END AS grp, COUNT(*) AS account_months,
       SUM(CASE WHEN withdrawn >= 0.9 * lim THEN 1 ELSE 0 END) AS at_limit_90pct,
       ROUND(100.0 * SUM(CASE WHEN withdrawn >= 0.9 * lim THEN 1 ELSE 0 END) / COUNT(*), 1) AS pct_at_limit,
       ROUND(AVG(withdrawn)) AS avg_withdrawn_exc
FROM tmp_user_month GROUP BY is_multi ORDER BY is_multi DESC;

-- Q4) Если кластер = один человек: сколько он вывел за месяц суммарно и во сколько раз это больше лимита одного аккаунта
--     (extra_over_one_limit - EXC, которые мультиаккаунты уже вывели СВЕРХ того, что позволил бы один аккаунт: эту сумму вы платите сейчас)
SELECT ym, COUNT(*) AS clusters, SUM(CASE WHEN combined > maxlim THEN 1 ELSE 0 END) AS over_one_limit,
       SUM(combined) AS combined_exc, SUM(GREATEST(combined - maxlim, 0)) AS extra_over_one_limit
FROM (SELECT c.dest, um.ym, SUM(um.withdrawn) AS combined, MAX(um.lim) AS maxlim
      FROM tmp_cluster c
      JOIN (SELECT DISTINCT dest, user_id FROM tmp_wd WHERE dest IS NOT NULL) du ON du.dest = c.dest
      JOIN tmp_user_month um ON um.user_id = du.user_id
      GROUP BY c.dest, um.ym) t
GROUP BY ym ORDER BY ym DESC;

-- Q5) Откуда заработок: квесты или бонусы (приветственный, реферальные). На аккаунт: среднее EXC и квестов, доля аккаунтов вообще без квестов
SELECT CASE WHEN m.user_id IS NULL THEN 'вне кластеров' ELSE 'в кластере' END AS grp, COUNT(DISTINCT u.id) AS accounts,
       ROUND(AVG(u.completed_quests), 1) AS avg_quests,
       ROUND(100.0 * SUM(CASE WHEN u.completed_quests = 0 THEN 1 ELSE 0 END) / COUNT(DISTINCT u.id), 1) AS pct_zero_quests,
       ROUND(SUM(CASE WHEN t.type = 'QUEST' THEN t.amount ELSE 0 END) / COUNT(DISTINCT u.id)) AS avg_quest_exc,
       ROUND(SUM(CASE WHEN t.type IN ('WELCOME_BONUS', 'REFERRAL_WELCOME') THEN t.amount ELSE 0 END) / COUNT(DISTINCT u.id)) AS avg_welcome_exc,
       ROUND(SUM(CASE WHEN t.type LIKE 'REFERRAL%' AND t.type <> 'REFERRAL_WELCOME' THEN t.amount ELSE 0 END) / COUNT(DISTINCT u.id)) AS avg_referral_exc,
       ROUND(SUM(CASE WHEN t.type NOT IN ('QUEST', 'WELCOME_BONUS', 'REFERRAL_WELCOME') AND t.type NOT LIKE 'REFERRAL%' AND t.amount > 0 THEN t.amount ELSE 0 END) / COUNT(DISTINCT u.id)) AS avg_other_exc
FROM app_users u
JOIN (SELECT DISTINCT user_id FROM tmp_wd) w ON w.user_id = u.id
LEFT JOIN tmp_multi_user m ON m.user_id = u.id
LEFT JOIN exc_transactions t ON t.user_id = u.id AND t.amount > 0
GROUP BY CASE WHEN m.user_id IS NULL THEN 'вне кластеров' ELSE 'в кластере' END;

-- Q6) Связи внутри кластеров: приглашали ли участники друг друга, общий отряд, пометка «подозрительный»
SELECT COUNT(*) AS member_accounts,
       SUM(CASE WHEN u.referred_by_telegram_id IN (SELECT x.telegram_id FROM app_users x JOIN tmp_multi_user mm ON mm.user_id = x.id) THEN 1 ELSE 0 END) AS invited_by_another_member,
       SUM(CASE WHEN u.fraud_suspect THEN 1 ELSE 0 END) AS flagged_suspect,
       SUM(CASE WHEN u.blocked THEN 1 ELSE 0 END) AS blocked_now
FROM app_users u JOIN tmp_multi_user m ON m.user_id = u.id;

DROP TABLE tmp_user_month;
DROP TABLE tmp_multi_user;
DROP TABLE tmp_cluster;
DROP TABLE tmp_wd;
