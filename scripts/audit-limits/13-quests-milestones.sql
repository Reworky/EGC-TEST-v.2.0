-- 13) Рубежи по числу выполненных квестов: на каком рубеже люди останавливаются. Для каждого числа квестов - сколько игроков и какая доля заходила за последние 7 дней
--     (резкий провал доли после рубежа = люди бросают именно здесь). Рубеж 3 - порог открытия вывода.
SELECT quests_band, COUNT(*) AS players,
       SUM(active7) AS active_7d,
       ROUND(100.0 * SUM(active7) / COUNT(*), 1) AS pct_active_7d,
       SUM(gone14) AS gone_14d_plus,
       ROUND(100.0 * SUM(gone14) / COUNT(*), 1) AS pct_gone_14d
FROM (SELECT CASE WHEN completed_quests = 0 THEN '00. 0 квестов' WHEN completed_quests = 1 THEN '01. 1 квест' WHEN completed_quests = 2 THEN '02. 2 квеста'
                  WHEN completed_quests = 3 THEN '03. 3 квеста (вывод открыт)' WHEN completed_quests = 4 THEN '04. 4 квеста' WHEN completed_quests = 5 THEN '05. 5 квестов'
                  WHEN completed_quests <= 9 THEN '06. 6-9' WHEN completed_quests <= 19 THEN '07. 10-19' WHEN completed_quests <= 49 THEN '08. 20-49' ELSE '09. 50+' END AS quests_band,
             CASE WHEN d >= DATEADD('DAY', -7, CURRENT_DATE) THEN 1 ELSE 0 END AS active7,
             CASE WHEN d < DATEADD('DAY', -14, CURRENT_DATE) THEN 1 ELSE 0 END AS gone14
      FROM (SELECT completed_quests,
                   CASE WHEN last_bot_activity_at IS NOT NULL AND (last_activity_date IS NULL OR CAST(last_bot_activity_at AS DATE) > last_activity_date)
                        THEN CAST(last_bot_activity_at AS DATE) ELSE COALESCE(last_activity_date, CAST(created_at AS DATE)) END AS d
            FROM app_users WHERE registration_completed = TRUE AND blocked = FALSE AND created_at <= DATEADD('DAY', -14, CURRENT_TIMESTAMP)) x) t
GROUP BY quests_band ORDER BY quests_band;
