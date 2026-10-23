-- Данные для расчёта коэффициента лимита вывода и цены EGC Pass (только чтение).
-- Запуск: cd /root/gamebot && git pull origin main && bash scripts/run-sql.sh scripts/pass-limit-data.sql
-- Лимит вывода по XP: <1000 - 10 000, 1000+ - 25 000, 5000+ - 50 000, 15000+ - 80 000, 35000+ - 100 000, 75000+ - 150 000 EXC в месяц.

-- 1) Заработок за 30 дней по игрокам (все положительные начисления, кроме возвратов): сколько игроков выше каждого порога
SELECT COUNT(*) AS earners,
       SUM(CASE WHEN e >= 10000 THEN 1 ELSE 0 END) AS ge_10k, SUM(CASE WHEN e >= 20000 THEN 1 ELSE 0 END) AS ge_20k,
       SUM(CASE WHEN e >= 30000 THEN 1 ELSE 0 END) AS ge_30k, SUM(CASE WHEN e >= 50000 THEN 1 ELSE 0 END) AS ge_50k,
       SUM(CASE WHEN e >= 100000 THEN 1 ELSE 0 END) AS ge_100k, MAX(e) AS max_earned
FROM (SELECT user_id, SUM(amount) AS e FROM exc_transactions
      WHERE amount > 0 AND type <> 'SHOP_REFUND' AND created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) GROUP BY user_id);

-- 2) Сколько EXC сверх лимита игроки не смогли бы вывести: при коэффициенте k выигрыш = сумма MIN(заработок, k*лимит) - MIN(заработок, лимит)
SELECT k.k AS coefficient,
       COUNT(*) AS players_who_gain,
       SUM(LEAST(x.e, k.k * x.lim) - LEAST(x.e, x.lim)) AS extra_exc_total,
       ROUND(SUM(LEAST(x.e, k.k * x.lim) - LEAST(x.e, x.lim)) / COUNT(*), 0) AS extra_exc_per_player
FROM (SELECT t.user_id, SUM(t.amount) AS e,
             CASE WHEN u.xp >= 75000 THEN 150000 WHEN u.xp >= 35000 THEN 100000 WHEN u.xp >= 15000 THEN 80000
                  WHEN u.xp >= 5000 THEN 50000 WHEN u.xp >= 1000 THEN 25000 ELSE 10000 END AS lim
      FROM exc_transactions t JOIN app_users u ON u.id = t.user_id
      WHERE t.amount > 0 AND t.type <> 'SHOP_REFUND' AND t.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) GROUP BY t.user_id, u.xp) x
CROSS JOIN (VALUES (2), (3), (5), (10)) k(k)
WHERE x.e > x.lim
GROUP BY k.k ORDER BY k.k;

-- 3) Траты игроков на донат за рубли за 30 дней (одобренные заявки): сколько игроков платят и сколько
SELECT COUNT(*) AS donors, SUM(s) AS total_rub, ROUND(AVG(s), 0) AS avg_rub,
       SUM(CASE WHEN s >= 500 THEN 1 ELSE 0 END) AS ge_500, SUM(CASE WHEN s >= 900 THEN 1 ELSE 0 END) AS ge_900, SUM(CASE WHEN s >= 1500 THEN 1 ELSE 0 END) AS ge_1500
FROM (SELECT user_id, SUM(price_rub) AS s FROM gem_purchase_requests
      WHERE status = 'APPROVED' AND created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) GROUP BY user_id);

-- 4) Сколько игроков с Pass сейчас и сколько покупок Pass было за всё время (по журналу Stars)
SELECT (SELECT COUNT(*) FROM app_users WHERE egc_pass_active_until > CURRENT_TIMESTAMP) AS pass_now,
       (SELECT COUNT(*) FROM stars_purchases WHERE item_type = 'starsitem:EGC_PASS') AS paid_purchases,
       (SELECT COUNT(*) FROM stars_purchases WHERE item_type = 'starsitem:EGC_PASS_TRIAL') AS trial_purchases;
