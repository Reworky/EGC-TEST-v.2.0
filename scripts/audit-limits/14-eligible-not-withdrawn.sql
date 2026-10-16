-- 14) Кто мог бы вывести, но не выводит: баланс >= 5 000 и открыт вывод (>=3 квестов, 3+ дня с регистрации), но заявки не было; и те, кому вывод закрыт порогом.
--     Показывает, не знают ли люди про вывод / не хотят ли деньги / не упираются ли в условия открытия.
SELECT grp, COUNT(*) AS players,
       SUM(CASE WHEN d >= DATEADD('DAY', -7, CURRENT_DATE) THEN 1 ELSE 0 END) AS active_7d
FROM (SELECT CASE WHEN u.completed_quests >= 3 AND u.created_at <= DATEADD('DAY', -3, CURRENT_TIMESTAMP) THEN 'вывод открыт, баланс >= 5 000, заявки не было'
                  ELSE 'баланс >= 5 000, вывод ещё закрыт (меньше 3 квестов или меньше 3 дней)' END AS grp,
             CASE WHEN u.last_bot_activity_at IS NOT NULL AND (u.last_activity_date IS NULL OR CAST(u.last_bot_activity_at AS DATE) > u.last_activity_date)
                  THEN CAST(u.last_bot_activity_at AS DATE) ELSE COALESCE(u.last_activity_date, CAST(u.created_at AS DATE)) END AS d
      FROM app_users u
      WHERE u.registration_completed = TRUE AND u.blocked = FALSE AND u.coins >= 5000
        AND NOT EXISTS (SELECT 1 FROM reward_requests rr JOIN reward_items ri ON ri.id = rr.reward_id WHERE rr.user_id = u.id AND ri.category = 'Вывод')) t
GROUP BY grp ORDER BY grp;
