-- 11) Динамика по неделям (16 недель): новые игроки, активные (были хоть одно начисление EXC), одобренные квесты, выплаты. Видно, что падает: приток или удержание.
WITH w AS (SELECT CAST(DATEADD('DAY', -(EXTRACT(ISO_DAY_OF_WEEK FROM d) - 1), CAST(d AS DATE)) AS DATE) AS week_start, kind, uid
           FROM (SELECT created_at AS d, 'new' AS kind, id AS uid FROM app_users WHERE registration_completed = TRUE
                 UNION ALL SELECT created_at, 'active', user_id FROM exc_transactions WHERE amount > 0
                 UNION ALL SELECT updated_at, 'quest', id FROM quest_submissions WHERE status = 'APPROVED'
                 UNION ALL SELECT COALESCE(rr.paid_at, rr.created_at), 'payout', rr.id FROM reward_requests rr JOIN reward_items ri ON ri.id = rr.reward_id
                           WHERE ri.category = 'Вывод' AND rr.status = 'APPROVED') x
           WHERE d >= DATEADD('DAY', -112, CURRENT_TIMESTAMP))
SELECT week_start,
       COUNT(DISTINCT CASE WHEN kind = 'new' THEN uid END) AS new_players,
       COUNT(DISTINCT CASE WHEN kind = 'active' THEN uid END) AS active_players,
       COUNT(CASE WHEN kind = 'quest' THEN 1 END) AS approved_quests,
       COUNT(CASE WHEN kind = 'payout' THEN 1 END) AS payouts
FROM w GROUP BY week_start ORDER BY week_start;
