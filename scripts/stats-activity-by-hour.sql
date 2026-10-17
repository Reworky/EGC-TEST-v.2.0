-- В какие часы игроки берут и закрывают квесты (для выбора окна «золотого часа»). Только чтение.
-- Время сервера = UTC (Москва = UTC+3, часовой пояс владельца UTC+7). Окно - 14 дней.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-activity-by-hour.sql

-- 1) Засчитанные квесты по часам суток (UTC): сколько квестов и сколько разных игроков.
SELECT HOUR(s.updated_at) AS hour_utc, COUNT(*) AS approved_quests, COUNT(DISTINCT s.user_id) AS players
FROM quest_submissions s
WHERE s.status = 'APPROVED' AND s.updated_at >= DATEADD('DAY', -14, CURRENT_TIMESTAMP)
GROUP BY HOUR(s.updated_at)
ORDER BY hour_utc;

-- 2) То же по ежедневному бонусу (заходы «за наградой»): по часам суток (UTC).
SELECT HOUR(t.created_at) AS hour_utc, COUNT(*) AS daily_bonus_claims, COUNT(DISTINCT t.user_id) AS players
FROM exc_transactions t
WHERE t.type = 'DAILY' AND t.created_at >= DATEADD('DAY', -14, CURRENT_TIMESTAMP)
GROUP BY HOUR(t.created_at)
ORDER BY hour_utc;

-- 3) Средняя награда за засчитанный квест (EXC) за 14 дней: от неё считается цена буста.
SELECT COUNT(*) AS approved, ROUND(AVG(COALESCE(s.awarded_coins, 0))) AS avg_exc, ROUND(MEDIAN(COALESCE(s.awarded_coins, 0))) AS median_exc
FROM quest_submissions s
WHERE s.status = 'APPROVED' AND s.updated_at >= DATEADD('DAY', -14, CURRENT_TIMESTAMP);
