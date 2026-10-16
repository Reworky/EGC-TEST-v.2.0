-- 5) Брошенные (не заходили 14+ дней): на каком балансе люди ушли и успели ли подать на вывод.
--    Много брошенных на 3 000-4 999 = порог 5 000 слишком далёк; много брошенных с 5 000+ без заявки = деньги им не интересны (или не знают про вывод).
SELECT balance_band, COUNT(*) AS dormant_players,
       SUM(CASE WHEN completed_quests >= 3 THEN 1 ELSE 0 END) AS had_3plus_quests,
       SUM(CASE WHEN requested = 1 THEN 1 ELSE 0 END) AS ever_requested_withdrawal
FROM (SELECT CASE WHEN u.coins < 1000 THEN '1. до 1 000' WHEN u.coins < 3000 THEN '2. 1 000-2 999' WHEN u.coins < 5000 THEN '3. 3 000-4 999'
                  WHEN u.coins < 10000 THEN '4. 5 000-9 999' WHEN u.coins < 25000 THEN '5. 10 000-24 999' ELSE '6. 25 000+' END AS balance_band,
             u.completed_quests,
             CASE WHEN EXISTS (SELECT 1 FROM reward_requests rr JOIN reward_items ri ON ri.id = rr.reward_id WHERE rr.user_id = u.id AND ri.category = 'Вывод') THEN 1 ELSE 0 END AS requested,
             CASE WHEN u.last_bot_activity_at IS NOT NULL AND (u.last_activity_date IS NULL OR CAST(u.last_bot_activity_at AS DATE) > u.last_activity_date)
                  THEN CAST(u.last_bot_activity_at AS DATE) ELSE COALESCE(u.last_activity_date, CAST(u.created_at AS DATE)) END AS last_day
      FROM app_users u WHERE u.registration_completed = TRUE AND u.blocked = FALSE) t
WHERE last_day < DATEADD('DAY', -14, CURRENT_DATE)
GROUP BY balance_band ORDER BY balance_band;
