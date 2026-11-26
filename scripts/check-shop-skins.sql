-- Все скины и дорогие CS2-товары в боевом магазине (только чтение).
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-shop-skins.sql
SELECT id, title, category, purchase_group, price_coins, active FROM reward_items
WHERE purchase_group = 'cs2_skin' OR LOWER(title) LIKE '%скин%' OR LOWER(title) LIKE '%skin%' OR title LIKE '%|%'
ORDER BY price_coins;
