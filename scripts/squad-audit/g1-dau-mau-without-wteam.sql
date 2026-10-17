-- Г1: DAU/MAU отрядов с W Team и без него по той же формуле, что в Аналитике (активность = любое действие в боте или открытие мини-аппа за 24 ч / 30 дней; в базе только registration_completed ИЛИ есть квесты).
-- Исключение отряда - по подстроке в названии; если у отряда другое написание, поменяйте 'w team' ниже (список отрядов смотрите в b2-squads.csv).
SELECT CASE WHEN LOWER(s.name) LIKE '%w team%' THEN 'W Team' ELSE 'остальные отряды' END AS grp,
       COUNT(*) AS members,
       SUM(CASE WHEN u.last_bot_activity_at >= DATEADD('HOUR', -24, CURRENT_TIMESTAMP) OR u.last_mini_app_open_at >= DATEADD('HOUR', -24, CURRENT_TIMESTAMP) THEN 1 ELSE 0 END) AS dau,
       SUM(CASE WHEN u.last_bot_activity_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) OR u.last_mini_app_open_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) THEN 1 ELSE 0 END) AS mau,
       ROUND(100.0 * SUM(CASE WHEN u.last_bot_activity_at >= DATEADD('HOUR', -24, CURRENT_TIMESTAMP) OR u.last_mini_app_open_at >= DATEADD('HOUR', -24, CURRENT_TIMESTAMP) THEN 1 ELSE 0 END)
             / NULLIF(SUM(CASE WHEN u.last_bot_activity_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) OR u.last_mini_app_open_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) THEN 1 ELSE 0 END), 0), 1) AS dau_mau_pct
FROM app_users u JOIN squads s ON s.id = u.squad_id
WHERE u.registration_completed = TRUE OR u.completed_quests > 0
GROUP BY CASE WHEN LOWER(s.name) LIKE '%w team%' THEN 'W Team' ELSE 'остальные отряды' END
ORDER BY grp
