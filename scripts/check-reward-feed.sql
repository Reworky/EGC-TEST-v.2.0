-- Почему награда магазина не видна в ленте выплат: что реально лежит в базе (только чтение).
-- Запуск: cd /root/gamebot && git pull origin main && bash scripts/run-sql.sh scripts/check-reward-feed.sql
-- В ленту идут заявки с категорией не «Вывод», статусом APPROVED и заполненным paid_at.

-- 1) Заявки на награды по статусам и наличию paid_at (категория NULL тоже учитывается)
SELECT r.status, COALESCE(i.category, '(категория пусто)') AS category, COUNT(*) AS cnt, SUM(CASE WHEN r.paid_at IS NULL THEN 1 ELSE 0 END) AS without_paid_at
FROM reward_requests r JOIN reward_items i ON i.id = r.reward_id
WHERE i.category IS NULL OR i.category <> 'Вывод'
GROUP BY r.status, COALESCE(i.category, '(категория пусто)') ORDER BY r.status, category;

-- 2) Последние 15 выданных наград (это и должно быть в ленте)
SELECT r.id, r.display_id, r.status, r.paid_at, r.fixed_rub_value, r.paid_price_coins, i.category, i.title
FROM reward_requests r JOIN reward_items i ON i.id = r.reward_id
WHERE (i.category IS NULL OR i.category <> 'Вывод') AND r.status = 'APPROVED'
ORDER BY COALESCE(r.paid_at, r.created_at) DESC LIMIT 15;
