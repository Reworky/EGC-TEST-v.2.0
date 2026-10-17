-- Г3: DAU/MAU «в отряде» и «без отряда» на СОПОСТАВИМЫХ игроках: аккаунт старше 14 дней и минимум 1 одобренный квест (отсекает однодневных новичков с закупа, которые раздувают MAU «без отряда»). Отдельно 1-2 квеста и 3+ квестов.
-- pct_active_30d - какая доля игроков группы заходила за 30 дней (в отличие от DAU/MAU, не зависит от размера MAU).
SELECT CASE WHEN u.squad_id IS NOT NULL THEN '1. в отряде' ELSE '2. без отряда' END AS grp,
       CASE WHEN u.completed_quests >= 3 THEN '3+ квестов' ELSE '1-2 квеста' END AS quests_band,
       COUNT(*) AS players,
       SUM(CASE WHEN u.last_bot_activity_at >= DATEADD('HOUR', -24, CURRENT_TIMESTAMP) OR u.last_mini_app_open_at >= DATEADD('HOUR', -24, CURRENT_TIMESTAMP) THEN 1 ELSE 0 END) AS dau,
       SUM(CASE WHEN u.last_bot_activity_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) OR u.last_mini_app_open_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) THEN 1 ELSE 0 END) AS mau,
       ROUND(100.0 * SUM(CASE WHEN u.last_bot_activity_at >= DATEADD('HOUR', -24, CURRENT_TIMESTAMP) OR u.last_mini_app_open_at >= DATEADD('HOUR', -24, CURRENT_TIMESTAMP) THEN 1 ELSE 0 END)
             / NULLIF(SUM(CASE WHEN u.last_bot_activity_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) OR u.last_mini_app_open_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) THEN 1 ELSE 0 END), 0), 1) AS dau_mau_pct,
       ROUND(100.0 * SUM(CASE WHEN u.last_bot_activity_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) OR u.last_mini_app_open_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) THEN 1 ELSE 0 END) / COUNT(*), 1) AS pct_active_30d
FROM app_users u
WHERE u.registration_completed = TRUE AND u.created_at <= DATEADD('DAY', -14, CURRENT_TIMESTAMP) AND u.completed_quests >= 1
GROUP BY CASE WHEN u.squad_id IS NOT NULL THEN '1. в отряде' ELSE '2. без отряда' END,
         CASE WHEN u.completed_quests >= 3 THEN '3+ квестов' ELSE '1-2 квеста' END
ORDER BY quests_band, grp
