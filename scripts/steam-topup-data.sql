-- Данные для выбора позиций «Пополнение Steam» в магазине (только чтение). Запуск на сервере:
--   cd /root/gamebot && git pull origin main && bash scripts/run-sql.sh scripts/steam-topup-data.sql
-- Цена товара для игрока = базовая цена EXC / Health Ratio (HealthRatioService.effectivePrice); базовая = рубли * 100 (паритет с выводом).
-- Покупка проходит, если у игрока хватает EXC и цена укладывается в остаток месячного лимита по его XP (ShopLimitService.checkMonthlySpendsLimit).

-- 1) Health Ratio сейчас
SELECT ROUND(ratio, 3) AS ratio_now, calculated_at FROM health_ratio_snapshots ORDER BY calculated_at DESC LIMIT 1;

-- 2) Игроки по уровням XP (от уровня зависит месячный лимит трат: <1000 XP 10к, 1000+ 25к, 5000+ 50к, 15000+ 80к, 35000+ 100к, 75000+ 150к EXC)
SELECT CASE WHEN xp >= 75000 THEN '75000+ (лимит 150к)' WHEN xp >= 35000 THEN '35000+ (лимит 100к)' WHEN xp >= 15000 THEN '15000+ (лимит 80к)'
            WHEN xp >= 5000 THEN '5000+ (лимит 50к)' WHEN xp >= 1000 THEN '1000+ (лимит 25к)' ELSE '<1000 (лимит 10к)' END AS xp_tier,
       COUNT(*) AS players, SUM(CASE WHEN coins >= 25000 THEN 1 ELSE 0 END) AS coins_25k_plus, SUM(CASE WHEN coins >= 50000 THEN 1 ELSE 0 END) AS coins_50k_plus,
       ROUND(AVG(coins), 0) AS avg_coins
FROM app_users WHERE registration_completed = TRUE AND blocked = FALSE
GROUP BY CASE WHEN xp >= 75000 THEN '75000+ (лимит 150к)' WHEN xp >= 35000 THEN '35000+ (лимит 100к)' WHEN xp >= 15000 THEN '15000+ (лимит 80к)'
              WHEN xp >= 5000 THEN '5000+ (лимит 50к)' WHEN xp >= 1000 THEN '1000+ (лимит 25к)' ELSE '<1000 (лимит 10к)' END
ORDER BY 1;

-- 3) Сколько игроков могли бы купить позицию ПРЯМО СЕЙЧАС (хватает EXC и остатка месячного лимита) при текущем Health Ratio
SELECT v.n AS nominal_rub, ROUND(v.n * 100 / r.ratio) AS price_exc_for_player,
       SUM(CASE WHEN u.coins >= ROUND(v.n * 100 / r.ratio) THEN 1 ELSE 0 END) AS enough_exc,
       SUM(CASE WHEN ROUND(v.n * 100 / r.ratio) <= (CASE WHEN u.xp >= 75000 THEN 150000 WHEN u.xp >= 35000 THEN 100000 WHEN u.xp >= 15000 THEN 80000
                  WHEN u.xp >= 5000 THEN 50000 WHEN u.xp >= 1000 THEN 25000 ELSE 10000 END) - COALESCE(u.monthly_withdrawn_exc, 0) THEN 1 ELSE 0 END) AS within_limit,
       SUM(CASE WHEN u.coins >= ROUND(v.n * 100 / r.ratio)
                 AND ROUND(v.n * 100 / r.ratio) <= (CASE WHEN u.xp >= 75000 THEN 150000 WHEN u.xp >= 35000 THEN 100000 WHEN u.xp >= 15000 THEN 80000
                  WHEN u.xp >= 5000 THEN 50000 WHEN u.xp >= 1000 THEN 25000 ELSE 10000 END) - COALESCE(u.monthly_withdrawn_exc, 0) THEN 1 ELSE 0 END) AS can_buy_now
FROM app_users u
CROSS JOIN (SELECT ratio FROM health_ratio_snapshots ORDER BY calculated_at DESC LIMIT 1) r
CROSS JOIN (VALUES (100), (150), (200), (250), (300), (500), (1000)) v(n)
WHERE u.registration_completed = TRUE AND u.blocked = FALSE
GROUP BY v.n, r.ratio ORDER BY v.n;

-- 4) Сколько игроков вообще уже покупали товары магазина и на какие суммы (для понимания спроса): заявки НЕ на вывод за 60 дней
SELECT ri.category, COUNT(*) AS requests, COUNT(DISTINCT rr.user_id) AS players, ROUND(AVG(COALESCE(rr.paid_price_coins, ri.price_coins)), 0) AS avg_exc
FROM reward_requests rr JOIN reward_items ri ON ri.id = rr.reward_id
WHERE ri.category <> 'Вывод' AND rr.status NOT IN ('CANCELLED', 'REJECTED') AND rr.created_at >= DATEADD('DAY', -60, CURRENT_TIMESTAMP)
GROUP BY ri.category ORDER BY requests DESC;
