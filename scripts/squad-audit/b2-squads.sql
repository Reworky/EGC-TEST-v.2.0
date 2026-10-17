-- Б2: все отряды, включая распавшиеся. У распавшихся участников уже нет (squad_id обнуляется), поэтому members_now, активность и квесты по ним = 0/пусто.
SELECT s.id AS squad_id, s.name, s.status, s.created_at, s.captain_telegram_id,
       (SELECT COUNT(*) FROM app_users m WHERE m.squad_id = s.id) AS members_now,
       (SELECT MAX(GREATEST(m.last_bot_activity_at, m.last_mini_app_open_at, CAST(m.last_activity_date AS TIMESTAMP))) FROM app_users m WHERE m.squad_id = s.id) AS last_member_activity,
       (SELECT COUNT(*) FROM quest_submissions qs JOIN app_users m ON m.id = qs.user_id
        WHERE m.squad_id = s.id AND qs.status = 'APPROVED' AND qs.updated_at >= DATEADD('DAY', -7, CURRENT_TIMESTAMP)) AS approved_quests_7d,
       (SELECT COUNT(*) FROM quest_submissions qs JOIN app_users m ON m.id = qs.user_id
        WHERE m.squad_id = s.id AND qs.status = 'APPROVED' AND qs.updated_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)) AS approved_quests_30d
FROM squads s
ORDER BY s.status, approved_quests_7d DESC
