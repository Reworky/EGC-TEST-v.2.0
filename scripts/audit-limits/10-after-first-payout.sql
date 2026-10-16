-- 10) Что происходит с игроком после первой выплаты (первая выплата >= 14 дней назад): остаются в клубе или уходят.
--     Для сравнения - те, кто набрал 5 000+ EXC, но так и не вывел.
WITH first_pay AS (SELECT rr.user_id, MIN(COALESCE(rr.paid_at, rr.created_at)) AS at
                   FROM reward_requests rr JOIN reward_items ri ON ri.id = rr.reward_id WHERE ri.category = 'Вывод' AND rr.status = 'APPROVED' GROUP BY rr.user_id),
earn AS (SELECT user_id, SUM(amount) AS earned FROM exc_transactions WHERE amount > 0 GROUP BY user_id),
u AS (SELECT id, CASE WHEN last_bot_activity_at IS NOT NULL AND (last_activity_date IS NULL OR CAST(last_bot_activity_at AS DATE) > last_activity_date)
                      THEN CAST(last_bot_activity_at AS DATE) ELSE COALESCE(last_activity_date, CAST(created_at AS DATE)) END AS last_day
      FROM app_users WHERE registration_completed = TRUE AND blocked = FALSE)
SELECT grp, COUNT(*) AS players,
       SUM(CASE WHEN last_day >= DATEADD('DAY', -7, CURRENT_DATE) THEN 1 ELSE 0 END) AS active_7d,
       ROUND(100.0 * SUM(CASE WHEN last_day >= DATEADD('DAY', -7, CURRENT_DATE) THEN 1 ELSE 0 END) / COUNT(*), 1) AS pct_active_7d
FROM (SELECT 'вывели (1-я выплата 14+ дн. назад)' AS grp, u.last_day FROM u JOIN first_pay f ON f.user_id = u.id WHERE f.at <= DATEADD('DAY', -14, CURRENT_TIMESTAMP)
      UNION ALL
      SELECT 'набрали 5 000+, не выводили', u.last_day FROM u JOIN earn e ON e.user_id = u.id LEFT JOIN first_pay f ON f.user_id = u.id WHERE e.earned >= 5000 AND f.user_id IS NULL) t
GROUP BY grp;
