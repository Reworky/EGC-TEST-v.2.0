-- Где EGC Pass перестаёт быть выгодным клубу: заработок игроков за 30 дней по уровням (только чтение).
-- Запуск: cd /root/gamebot && git pull origin main && bash scripts/run-sql.sh scripts/pass-breakeven-by-level.sql
-- Уровни по XP (UserService.LEVEL_TIERS): 1 <1000, 2 от 1000, 3 от 5000, 4 от 15000, 5 от 35000, 6 от 75000, 7 от 150000, 8 от 300000.
-- Модель: расходы клуба на подписчика = улучшенный сундук (~18-33 ₽) + бонус Pass +10% EXC (потолок 10 000 EXC/мес) с надбавкой уровня + потерянная наценка доната.
-- Выручка подписки ≈ 76 ₽/мес (65 ⭐), пробный месяц ≈ 39 ₽. Ориентир по заработку, при котором бонус упирается в потолок: ≈ 100 000 EXC в месяц.

-- 1) Заработок за 30 дней по уровням: сколько игроков, средний и максимальный заработок, сколько упёрлись бы в потолок бонуса (≥ 100 000 EXC)
SELECT CASE WHEN u.xp >= 300000 THEN 8 WHEN u.xp >= 150000 THEN 7 WHEN u.xp >= 75000 THEN 6 WHEN u.xp >= 35000 THEN 5
            WHEN u.xp >= 15000 THEN 4 WHEN u.xp >= 5000 THEN 3 WHEN u.xp >= 1000 THEN 2 ELSE 1 END AS level_no,
       COUNT(*) AS earners, ROUND(AVG(x.e), 0) AS avg_earned, MAX(x.e) AS max_earned,
       SUM(CASE WHEN x.e >= 50000 THEN 1 ELSE 0 END) AS ge_50k, SUM(CASE WHEN x.e >= 100000 THEN 1 ELSE 0 END) AS ge_100k
FROM (SELECT user_id, SUM(amount) AS e FROM exc_transactions
      WHERE amount > 0 AND type <> 'SHOP_REFUND' AND created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) GROUP BY user_id) x
JOIN app_users u ON u.id = x.user_id
GROUP BY level_no ORDER BY level_no;

-- 2) Донаты за 30 дней по игрокам (рубли): кому Pass выгоден по скидке - это тот, у кого здесь крупные суммы
SELECT user_id, SUM(price_rub) AS donated_rub, COUNT(*) AS purchases
FROM gem_purchase_requests WHERE status = 'APPROVED' AND created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)
GROUP BY user_id ORDER BY donated_rub DESC LIMIT 20;

-- 3) Игроки с Pass сейчас: уровень, заработок за 30 дней, бонус Pass в этом месяце
SELECT u.telegram_id, u.nickname, u.xp, u.egc_pass_active_until, u.egc_pass_boost_monthly_exc,
       COALESCE((SELECT SUM(amount) FROM exc_transactions t WHERE t.user_id = u.id AND t.amount > 0 AND t.type <> 'SHOP_REFUND'
                 AND t.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)), 0) AS earned_30d
FROM app_users u WHERE u.egc_pass_active_until > CURRENT_TIMESTAMP;
