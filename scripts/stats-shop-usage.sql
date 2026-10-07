-- Чем реально пользуются в магазине: что покупают чаще всего и кто. Только чтение.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-shop-usage.sql
-- Окно: последние 90 дней (для 30-дневных цифр смотрите колонки с _30d).

-- 1) «Предметы» (расходники за EXC: реролл, бусты, страховка, слоты, снятие кулдауна, титулы, подарки): по видам.
SELECT CASE
         WHEN t.description LIKE 'Титул:%' THEN 'Титул'
         WHEN t.description LIKE 'Подарок буст%' THEN 'Подарок буст'
         ELSE t.description
       END AS item,
       COUNT(*) AS purchases_90d,
       COUNT(DISTINCT t.user_id) AS buyers_90d,
       SUM(CASE WHEN t.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) THEN 1 ELSE 0 END) AS purchases_30d,
       -SUM(t.amount) AS exc_spent_90d
FROM exc_transactions t
WHERE t.type = 'SINK' AND t.amount < 0 AND t.created_at >= DATEADD('DAY', -90, CURRENT_TIMESTAMP)
GROUP BY CASE
         WHEN t.description LIKE 'Титул:%' THEN 'Титул'
         WHEN t.description LIKE 'Подарок буст%' THEN 'Подарок буст'
         ELSE t.description
       END
ORDER BY purchases_90d DESC;

-- 2) Магазин наград (карты, гемы за EXC, значки, рамки, футболка, Council): по товарам, без вывода.
SELECT i.title, i.category, i.price_coins,
       COUNT(*) AS orders_90d,
       COUNT(DISTINCT r.user_id) AS buyers_90d,
       SUM(CASE WHEN r.status = 'APPROVED' THEN 1 ELSE 0 END) AS approved,
       SUM(CASE WHEN r.status IN ('REJECTED', 'CANCELLED') THEN 1 ELSE 0 END) AS rejected_or_cancelled
FROM reward_requests r JOIN reward_items i ON i.id = r.reward_id
WHERE r.created_at >= DATEADD('DAY', -90, CURRENT_TIMESTAMP) AND i.category <> 'Вывод'
GROUP BY i.id, i.title, i.category, i.price_coins
ORDER BY orders_90d DESC
LIMIT 40;

-- 3) Вывод по способам: рубли, TON, Stars (число заявок, игроков, EXC).
SELECT CASE
         WHEN i.title LIKE 'Telegram Stars%' THEN 'Stars'
         WHEN i.title LIKE '%GRAM%' OR i.title LIKE '%TON%' THEN 'TON'
         ELSE 'Рубли'
       END AS method,
       COUNT(*) AS requests_90d,
       COUNT(DISTINCT r.user_id) AS players_90d,
       SUM(COALESCE(r.paid_price_coins, i.price_coins)) AS exc_90d
FROM reward_requests r JOIN reward_items i ON i.id = r.reward_id
WHERE r.created_at >= DATEADD('DAY', -90, CURRENT_TIMESTAMP) AND i.category = 'Вывод'
  AND r.status <> 'CANCELLED' AND r.status <> 'REJECTED'
GROUP BY CASE
         WHEN i.title LIKE 'Telegram Stars%' THEN 'Stars'
         WHEN i.title LIKE '%GRAM%' OR i.title LIKE '%TON%' THEN 'TON'
         ELSE 'Рубли'
       END
ORDER BY requests_90d DESC;

-- 4) Какие номиналы Stars выводят (ступени лестницы).
SELECT i.title, i.price_coins, COUNT(*) AS requests_90d, COUNT(DISTINCT r.user_id) AS players_90d
FROM reward_requests r JOIN reward_items i ON i.id = r.reward_id
WHERE r.created_at >= DATEADD('DAY', -90, CURRENT_TIMESTAMP) AND i.title LIKE 'Telegram Stars%'
  AND r.status <> 'CANCELLED' AND r.status <> 'REJECTED'
GROUP BY i.id, i.title, i.price_coins
ORDER BY i.price_coins;

-- 5) Донат за деньги (гемы и пропуски): по играм, товарам и способу оплаты.
SELECT g.game_name, COALESCE(g.item_label, CAST(g.gems AS VARCHAR) || ' гемов') AS item, g.payment_method,
       COUNT(*) AS orders_90d, COUNT(DISTINCT g.user_id) AS buyers_90d, SUM(g.price_rub) AS rub_90d
FROM gem_purchase_requests g
WHERE g.created_at >= DATEADD('DAY', -90, CURRENT_TIMESTAMP) AND g.status <> 'REJECTED'
GROUP BY g.game_name, COALESCE(g.item_label, CAST(g.gems AS VARCHAR) || ' гемов'), g.payment_method
ORDER BY orders_90d DESC
LIMIT 30;

-- 6) Покупки за Stars (EGC Pass, реролл сундука и др.): по типам.
SELECT p.item_type, COUNT(*) AS purchases_90d, COUNT(DISTINCT p.telegram_id) AS buyers_90d, SUM(p.stars_amount) AS stars_90d
FROM stars_purchases p
WHERE p.created_at >= DATEADD('DAY', -90, CURRENT_TIMESTAMP)
GROUP BY p.item_type
ORDER BY purchases_90d DESC;

-- 7) Воронка: какая доля активных за 30 дней игроков вообще что-то покупала (любой путь).
SELECT (SELECT COUNT(DISTINCT s.user_id) FROM quest_submissions s WHERE s.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)) AS active_players_30d,
       (SELECT COUNT(DISTINCT t.user_id) FROM exc_transactions t WHERE t.type = 'SINK' AND t.amount < 0 AND t.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)) AS bought_items_30d,
       (SELECT COUNT(DISTINCT r.user_id) FROM reward_requests r JOIN reward_items i ON i.id = r.reward_id
          WHERE i.category <> 'Вывод' AND r.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)) AS shop_orders_30d,
       (SELECT COUNT(DISTINCT r.user_id) FROM reward_requests r JOIN reward_items i ON i.id = r.reward_id
          WHERE i.category = 'Вывод' AND r.status <> 'CANCELLED' AND r.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)) AS withdrew_30d,
       (SELECT COUNT(DISTINCT g.user_id) FROM gem_purchase_requests g WHERE g.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)) AS donated_30d;

-- 8) Распределение баланса EXC у активных игроков: сколько человек могут позволить себе разные ступени цен
--    (ориентир для «первой цели»: 5 000 / 10 000 / 25 000 / 50 000 EXC).
SELECT SUM(CASE WHEN u.coins >= 5000 THEN 1 ELSE 0 END) AS has_5k,
       SUM(CASE WHEN u.coins >= 10000 THEN 1 ELSE 0 END) AS has_10k,
       SUM(CASE WHEN u.coins >= 25000 THEN 1 ELSE 0 END) AS has_25k,
       SUM(CASE WHEN u.coins >= 50000 THEN 1 ELSE 0 END) AS has_50k,
       COUNT(*) AS active_players
FROM app_users u
WHERE u.id IN (SELECT s.user_id FROM quest_submissions s WHERE s.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP));
