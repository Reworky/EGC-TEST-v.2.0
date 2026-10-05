-- Реклама AdsGram в боте: заполняемость и выданные награды по дням. Только чтение.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-bot-ads.sql

-- 1) Запросы объявления по дням: всего, найдено, no-fill, процент заполнения.
SELECT day, requests, filled, empty,
       ROUND(100.0 * filled / NULLIF(requests, 0), 1) AS fill_pct
FROM bot_ad_stats
ORDER BY day DESC
LIMIT 30;

-- 2) Награды за рекламу в боте по дням: сколько наград, сколько игроков, сколько EXC выдано.
SELECT CAST(created_at AS DATE) AS day,
       COUNT(*) AS rewards,
       COUNT(DISTINCT user_id) AS players,
       SUM(amount) AS exc_paid
FROM exc_transactions
WHERE type = 'AD_REWARD' AND description LIKE 'Просмотр рекламы в боте%'
GROUP BY CAST(created_at AS DATE)
ORDER BY day DESC
LIMIT 30;
