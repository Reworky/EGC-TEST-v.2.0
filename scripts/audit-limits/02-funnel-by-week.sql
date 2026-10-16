-- 2) То же по неделям регистрации (последние 10 недель): растёт или падает доля дошедших до квестов, 5 000 EXC и вывода.
WITH u AS (SELECT id, completed_quests,
                  CAST(DATEADD('DAY', -(EXTRACT(ISO_DAY_OF_WEEK FROM created_at) - 1), CAST(created_at AS DATE)) AS DATE) AS week_start
           FROM app_users WHERE registration_completed = TRUE AND blocked = FALSE AND created_at >= DATEADD('DAY', -70, CURRENT_TIMESTAMP)),
earn AS (SELECT user_id, SUM(amount) AS earned FROM exc_transactions WHERE amount > 0 GROUP BY user_id),
wd AS (SELECT rr.user_id, SUM(CASE WHEN rr.status = 'APPROVED' THEN 1 ELSE 0 END) AS paid
       FROM reward_requests rr JOIN reward_items ri ON ri.id = rr.reward_id WHERE ri.category = 'Вывод' GROUP BY rr.user_id)
SELECT u.week_start, COUNT(*) AS registered,
       SUM(CASE WHEN u.completed_quests >= 3 THEN 1 ELSE 0 END) AS quests_3plus,
       SUM(CASE WHEN COALESCE(e.earned, 0) >= 5000 THEN 1 ELSE 0 END) AS earned_5000,
       SUM(CASE WHEN COALESCE(w.paid, 0) >= 1 THEN 1 ELSE 0 END) AS paid_out,
       ROUND(100.0 * SUM(CASE WHEN COALESCE(e.earned, 0) >= 5000 THEN 1 ELSE 0 END) / COUNT(*), 1) AS pct_earned_5000
FROM u LEFT JOIN earn e ON e.user_id = u.id LEFT JOIN wd w ON w.user_id = u.id
GROUP BY u.week_start ORDER BY u.week_start;
