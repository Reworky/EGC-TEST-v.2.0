-- Какими разделами и фишками бота реально пользуются: сколько игроков за 30 дней получили EXC/XP через каждую (по журналу операций exc_transactions) и динамика к прошлым 30 дням.
-- Нужен для плана постов канала «про что в боте мало кто знает». Только чтение. Время сервера.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-feature-usage.sql

-- 1) Активная база: сколько игроков имели любую операцию за 30 дней.
SELECT COUNT(DISTINCT user_id) AS active_30d
FROM exc_transactions
WHERE created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP);

-- 2) По видам операций за последние 30 дней: сколько игроков, сколько операций, сколько EXC, доля активной базы. Типы: QUEST - квесты, DAILY - ежедневный бонус, CHEST/DAILY_TASKS - сундук заданий дня,
--    DAILY_CODE - код дня, CLUB_GOAL/WEEKLY_GOAL - цели, SQUAD_* - отряды, REFERRAL_* - рефералка, TOURNAMENT, SOCIAL_TASK, AD_REWARD - реклама, SINK - траты на «Предметы», WITHDRAWAL - вывод.
SELECT t.type,
       COUNT(DISTINCT t.user_id) AS users_30d,
       COUNT(*) AS ops,
       SUM(t.amount) AS exc_sum,
       ROUND(100.0 * COUNT(DISTINCT t.user_id) / (SELECT COUNT(DISTINCT user_id) FROM exc_transactions WHERE created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)), 1) AS pct_of_active
FROM exc_transactions t
WHERE t.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)
GROUP BY t.type
ORDER BY users_30d DESC;

-- 3) То же за предыдущие 30 дней (31-60 дней назад): чтобы видеть, что растёт, а что падает.
SELECT t.type, COUNT(DISTINCT t.user_id) AS users_prev30, COUNT(*) AS ops_prev30
FROM exc_transactions t
WHERE t.created_at >= DATEADD('DAY', -60, CURRENT_TIMESTAMP) AND t.created_at < DATEADD('DAY', -30, CURRENT_TIMESTAMP)
GROUP BY t.type
ORDER BY users_prev30 DESC;

-- 4) Рефералка и EGC Pass среди всех зарегистрированных: сколько уже кого-то приглашали и у скольких Pass активен.
SELECT COUNT(*) AS registered,
       SUM(CASE WHEN invited_friends > 0 THEN 1 ELSE 0 END) AS invited_someone,
       SUM(CASE WHEN egc_pass_active_until > CURRENT_TIMESTAMP THEN 1 ELSE 0 END) AS pass_active_now
FROM app_users
WHERE registration_completed = TRUE;
