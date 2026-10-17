-- Г2: вступившие в отряд за 30 дней - квесты за 14 дней до и за 14 дней после вступления. Точной даты вступления в базе нет, поэтому берутся только вступившие ПО ЗАЯВКЕ (дата заявки приблизительно = дата вступления, вступление могло быть позже одобрения). Вступившие по коду/открытому набору в выборку не попадают.
-- days_observed_after - сколько дней после вступления уже прошло (максимум 14): если меньше 14, «после» неполное.
SELECT r.user_id, r.squad_id, r.created_at AS join_request_at,
       (SELECT COUNT(*) FROM quest_submissions q WHERE q.user_id = r.user_id AND q.status = 'APPROVED'
          AND q.updated_at >= DATEADD('DAY', -14, r.created_at) AND q.updated_at < r.created_at) AS approved_q_14d_before,
       (SELECT COUNT(*) FROM quest_submissions q WHERE q.user_id = r.user_id AND q.status = 'APPROVED'
          AND q.updated_at >= r.created_at AND q.updated_at < DATEADD('DAY', 14, r.created_at)) AS approved_q_14d_after,
       LEAST(14, DATEDIFF('DAY', r.created_at, CURRENT_TIMESTAMP)) AS days_observed_after,
       CASE WHEN u.squad_id = r.squad_id THEN 'да' ELSE 'нет' END AS still_in_squad
FROM squad_join_requests r JOIN app_users u ON u.id = r.user_id
WHERE r.status = 'APPROVED' AND r.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)
ORDER BY r.created_at
