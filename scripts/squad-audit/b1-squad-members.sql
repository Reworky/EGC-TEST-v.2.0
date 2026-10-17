-- Б1: все игроки в отрядах (текущее членство). Дата вступления в отряд в базе НЕ хранится: показана дата последней одобренной заявки на вступление (если вступал по заявке; при вступлении по коду/открытому набору пусто).
WITH appr AS (SELECT user_id, updated_at, ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY updated_at) AS rn FROM quest_submissions WHERE status = 'APPROVED'),
q AS (SELECT user_id, COUNT(*) AS total_q,
             SUM(CASE WHEN updated_at >= DATEADD('DAY', -7, CURRENT_TIMESTAMP) THEN 1 ELSE 0 END) AS q7,
             SUM(CASE WHEN updated_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) THEN 1 ELSE 0 END) AS q30,
             MAX(CASE WHEN rn = 1 THEN updated_at END) AS first_q_at,
             MAX(CASE WHEN rn = 2 THEN updated_at END) AS second_q_at
      FROM appr GROUP BY user_id),
jr AS (SELECT user_id, squad_id, MAX(created_at) AS join_request_at FROM squad_join_requests WHERE status = 'APPROVED' GROUP BY user_id, squad_id)
SELECT u.id AS user_id, u.telegram_id, s.id AS squad_id, s.name AS squad, s.status AS squad_status,
       u.created_at AS registered_at, jr.join_request_at AS squad_join_request_at,
       CASE WHEN u.referred_by_telegram_id IS NOT NULL THEN 'да' ELSE 'нет' END AS referred,
       GREATEST(u.last_bot_activity_at, u.last_mini_app_open_at, CAST(u.last_activity_date AS TIMESTAMP)) AS last_activity_at,
       COALESCE(q.q7, 0) AS approved_q_7d, COALESCE(q.q30, 0) AS approved_q_30d, COALESCE(q.total_q, 0) AS approved_q_total,
       q.first_q_at, q.second_q_at
FROM app_users u
JOIN squads s ON s.id = u.squad_id
LEFT JOIN jr ON jr.user_id = u.id AND jr.squad_id = u.squad_id
LEFT JOIN q ON q.user_id = u.id
WHERE u.registration_completed = TRUE
ORDER BY s.name, u.created_at
