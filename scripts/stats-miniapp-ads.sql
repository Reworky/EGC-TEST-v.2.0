-- Реклама за награду по местам показа (мини-апп + бот): сколько раз нажали «смотреть» и сколько наград выдано. Только чтение.
-- Места: quests (Квесты -> Реклама), wallet (Кошелёк), wheel (колесо за рекламу), streak (восстановление серии), bot (реклама в боте), other.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-miniapp-ads.sql

-- 1) За 14 дней по местам: нажатий, наград, процент доведённых до награды.
SELECT placement,
       SUM(requests) AS requests,
       SUM(rewards) AS rewards,
       ROUND(100.0 * SUM(rewards) / NULLIF(SUM(requests), 0), 1) AS reward_pct
FROM ad_placement_stats
WHERE day >= DATEADD('DAY', -14, CURRENT_DATE)
GROUP BY placement
ORDER BY rewards DESC;

-- 2) По дням (14 дней): всего нажатий и наград.
SELECT day, SUM(requests) AS requests, SUM(rewards) AS rewards
FROM ad_placement_stats
WHERE day >= DATEADD('DAY', -14, CURRENT_DATE)
GROUP BY day
ORDER BY day DESC;

-- 3) Сколько EXC выдано за рекламу по дням (все места, кроме серии, где награда не EXC) и сколько игроков.
SELECT CAST(created_at AS DATE) AS day, COUNT(*) AS rewards, COUNT(DISTINCT user_id) AS players, SUM(amount) AS exc_paid
FROM exc_transactions
WHERE type = 'AD_REWARD' AND created_at >= DATEADD('DAY', -14, CURRENT_TIMESTAMP)
GROUP BY CAST(created_at AS DATE)
ORDER BY day DESC;
