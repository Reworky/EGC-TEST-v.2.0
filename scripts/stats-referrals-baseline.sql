-- Замер «до» для плана «Позови одного»: какая доля игроков позвала хотя бы одного друга. Только чтение.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-referrals-baseline.sql

-- 1) Все зарегистрированные игроки: сколько позвали 0 / 1 / 2-4 / 5+ друзей.
SELECT CASE WHEN invited_friends = 0 THEN '0 друзей'
            WHEN invited_friends = 1 THEN '1 друг'
            WHEN invited_friends <= 4 THEN '2-4 друга'
            ELSE '5+ друзей' END AS bucket,
       COUNT(*) AS players
FROM app_users
WHERE registration_completed = TRUE
GROUP BY CASE WHEN invited_friends = 0 THEN '0 друзей'
              WHEN invited_friends = 1 THEN '1 друг'
              WHEN invited_friends <= 4 THEN '2-4 друга'
              ELSE '5+ друзей' END
ORDER BY bucket;

-- 2) Только активные за 30 дней и с хотя бы одним выполненным квестом (целевая аудитория плана): доля позвавших хотя бы одного.
SELECT COUNT(*) AS active_players,
       SUM(CASE WHEN invited_friends >= 1 THEN 1 ELSE 0 END) AS invited_at_least_one,
       ROUND(100.0 * SUM(CASE WHEN invited_friends >= 1 THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 1) AS invited_pct
FROM app_users
WHERE registration_completed = TRUE
  AND completed_quests >= 1
  AND last_activity_date >= DATEADD('DAY', -30, CURRENT_DATE);

-- 3) Сколько игроков пришло по приглашению и сколько из них дошло до первого квеста.
SELECT COUNT(*) AS referred_players,
       SUM(CASE WHEN completed_quests >= 1 THEN 1 ELSE 0 END) AS referred_with_quest
FROM app_users
WHERE referred_by_telegram_id IS NOT NULL;
