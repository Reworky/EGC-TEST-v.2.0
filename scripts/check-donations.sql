-- Донат гемов и пропусков за деньги: что происходило с заявками за 90 дней (почему «выполненных» нет). Только чтение.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-donations.sql

-- 1) Статусы по дням: сколько заявок и чем закончились.
SELECT g.status, g.payment_method, COUNT(*) AS cnt, COUNT(DISTINCT g.user_id) AS players, SUM(g.price_rub) AS rub
FROM gem_purchase_requests g
WHERE g.created_at >= DATEADD('DAY', -90, CURRENT_TIMESTAMP)
GROUP BY g.status, g.payment_method
ORDER BY cnt DESC;

-- 2) Последние 30 заявок: что заказывали, чем платили, статус, причина отклонения.
SELECT g.id, g.display_id, g.created_at, g.game_name, g.item_label, g.gems, g.price_rub, g.payment_method, g.status, g.reject_reason
FROM gem_purchase_requests g
ORDER BY g.created_at DESC
LIMIT 30;
