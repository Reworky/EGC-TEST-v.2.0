-- EGC Pass: сколько выводят владельцы подписки против выручки с неё (только чтение).
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/pass-withdrawals-check.sql
-- Выручка подписки ≈ 76 ₽ (65 ⭐ x ≈1,17 ₽). В рублях выплата = EXC x курс фонда / 100 (при фонде 36%: 125 000 EXC ≈ 450 ₽).

-- 1) Владельцы Pass сейчас: уровень, лимит по XP (без Pass), выведено в этом месяце, заработок за 30 дней
SELECT u.telegram_id, u.nickname, u.xp, u.egc_pass_active_until,
       CASE WHEN u.xp >= 75000 THEN 150000 WHEN u.xp >= 35000 THEN 100000 WHEN u.xp >= 15000 THEN 80000
            WHEN u.xp >= 5000 THEN 50000 WHEN u.xp >= 1000 THEN 25000 ELSE 10000 END AS base_limit,
       u.monthly_withdrawn_exc, u.coins,
       COALESCE((SELECT SUM(amount) FROM exc_transactions t WHERE t.user_id = u.id AND t.amount > 0 AND t.type <> 'SHOP_REFUND'
                 AND t.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)), 0) AS earned_30d
FROM app_users u WHERE u.egc_pass_active_until > CURRENT_TIMESTAMP
ORDER BY u.monthly_withdrawn_exc DESC;

-- 2) Покупки Pass за всё время (платные, пробные, подарки)
SELECT item_type, COUNT(*) AS purchases, SUM(stars_amount) AS stars_total
FROM stars_purchases WHERE item_type LIKE 'starsitem:EGC_PASS%' OR item_type LIKE '%PASS_GIFT%'
GROUP BY item_type;
