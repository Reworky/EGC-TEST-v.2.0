-- Б3: журнал активных дней (user_activity_days) по игрокам в отрядах за 30 дней. Журнал ведётся только с 2026-10-15: раньше по дням активность не хранилась (есть лишь «последняя активность»).
SELECT d.user_id, s.name AS squad, d.activity_day, d.source
FROM user_activity_days d
JOIN app_users u ON u.id = d.user_id
JOIN squads s ON s.id = u.squad_id
WHERE u.registration_completed = TRUE AND d.activity_day >= DATEADD('DAY', -30, CURRENT_DATE)
ORDER BY d.user_id, d.activity_day
