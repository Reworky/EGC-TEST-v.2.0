-- В3: пары участников одного отряда, чьи заявки на квесты создаются в пределах 2 минут друг от друга; показаны пары с 3+ такими совпадениями (регулярно). Окно - 60 дней.
SELECT ua.squad_id, a.user_id AS user_a, b.user_id AS user_b,
       COUNT(*) AS near_simultaneous_pairs,
       COUNT(DISTINCT CAST(a.created_at AS DATE)) AS distinct_days,
       MIN(a.created_at) AS first_seen, MAX(a.created_at) AS last_seen
FROM quest_submissions a
JOIN app_users ua ON ua.id = a.user_id AND ua.squad_id IS NOT NULL AND ua.registration_completed = TRUE
JOIN app_users ub ON ub.squad_id = ua.squad_id AND ub.id > ua.id AND ub.registration_completed = TRUE
JOIN quest_submissions b ON b.user_id = ub.id AND ABS(DATEDIFF('SECOND', a.created_at, b.created_at)) <= 120
WHERE a.status <> 'DRAFT' AND b.status <> 'DRAFT' AND a.created_at >= DATEADD('DAY', -60, CURRENT_TIMESTAMP)
GROUP BY ua.squad_id, a.user_id, b.user_id
HAVING COUNT(*) >= 3
ORDER BY near_simultaneous_pairs DESC
