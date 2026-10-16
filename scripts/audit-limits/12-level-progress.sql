-- 12) Как игроки продвигаются по уровням (от XP зависит месячный лимит вывода): сколько на каждой ступени и сколько из них заходили за последние 30 дней.
SELECT level, COUNT(*) AS players,
       SUM(CASE WHEN completed_quests >= 3 THEN 1 ELSE 0 END) AS with_3plus_quests,
       SUM(active30) AS active_30d
FROM (SELECT CASE WHEN xp >= 300000 THEN '8. Амбассадор (300k XP)' WHEN xp >= 150000 THEN '7. Чемпион (150k)' WHEN xp >= 75000 THEN '6. Герой (75k)'
                  WHEN xp >= 35000 THEN '5. Легенда (35k)' WHEN xp >= 15000 THEN '4. Элита (15k)' WHEN xp >= 5000 THEN '3. Ветеран (5k)'
                  WHEN xp >= 1000 THEN '2. Игрок (1k)' ELSE '1. Новичок (до 1k XP)' END AS level,
             completed_quests,
             CASE WHEN COALESCE(last_activity_date, CAST(created_at AS DATE)) >= DATEADD('DAY', -30, CURRENT_DATE) OR last_bot_activity_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) THEN 1 ELSE 0 END AS active30
      FROM app_users WHERE registration_completed = TRUE AND blocked = FALSE) t
GROUP BY level ORDER BY level;
