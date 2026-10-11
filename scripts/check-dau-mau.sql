-- Проверка DAU/MAU: как считает бот сейчас и как получится при «честных» определениях. Только читает базу.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-dau-mau.sql
--
-- «Как в боте» = UserService.getEngagementReport: DAU/MAU считаются по трём полям (last_activity_date, last_bot_activity_at,
--   last_mini_app_open_at), БЕЗ фильтра по регистрации и со смешанными окнами (даты «вчера/сегодня» + скользящие 24 ч).
-- «Календарно» = DAU: активен СЕГОДНЯ (по дате); MAU: активен за последние 30 календарных дней, включая сегодня.
-- Сегменты показывают, из кого на самом деле состоит MAU: заходившие один раз /start vs те, кто что-то делал.

WITH t AS (
  SELECT u.id,
    CASE WHEN u.registration_completed = TRUE THEN 1 ELSE 0 END AS reg,
    CASE WHEN u.nickname IS NOT NULL AND u.nickname <> '' THEN 1 ELSE 0 END AS has_nick,
    CASE WHEN u.completed_quests > 0 THEN 1 ELSE 0 END AS quester,
    CASE WHEN u.created_at <= DATEADD('DAY', -7, CURRENT_TIMESTAMP) THEN 1 ELSE 0 END AS older7,
    -- как в боте
    CASE WHEN u.last_activity_date >= CAST(DATEADD('DAY', -1, CURRENT_TIMESTAMP) AS DATE)
           OR u.last_bot_activity_at >= DATEADD('DAY', -1, CURRENT_TIMESTAMP)
           OR u.last_mini_app_open_at >= DATEADD('DAY', -1, CURRENT_TIMESTAMP) THEN 1 ELSE 0 END AS dau_bot,
    CASE WHEN u.last_activity_date >= CAST(DATEADD('DAY', -30, CURRENT_TIMESTAMP) AS DATE)
           OR u.last_bot_activity_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)
           OR u.last_mini_app_open_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) THEN 1 ELSE 0 END AS mau_bot,
    -- календарно
    CASE WHEN u.last_activity_date = CURRENT_DATE
           OR CAST(u.last_bot_activity_at AS DATE) = CURRENT_DATE
           OR CAST(u.last_mini_app_open_at AS DATE) = CURRENT_DATE THEN 1 ELSE 0 END AS dau_cal,
    CASE WHEN u.last_activity_date >= DATEADD('DAY', -29, CURRENT_DATE)
           OR CAST(u.last_bot_activity_at AS DATE) >= DATEADD('DAY', -29, CURRENT_DATE)
           OR CAST(u.last_mini_app_open_at AS DATE) >= DATEADD('DAY', -29, CURRENT_DATE) THEN 1 ELSE 0 END AS mau_cal
  FROM app_users u
)
SELECT segment, accounts, mau_bot, dau_bot,
       CASE WHEN mau_bot = 0 THEN 0 ELSE ROUND(dau_bot * 100.0 / mau_bot, 1) END AS pct_bot,
       mau_cal, dau_cal,
       CASE WHEN mau_cal = 0 THEN 0 ELSE ROUND(dau_cal * 100.0 / mau_cal, 1) END AS pct_calendar
FROM (
  SELECT '1. все аккаунты (как в боте сейчас)' AS segment, COUNT(*) AS accounts, SUM(mau_bot) AS mau_bot, SUM(dau_bot) AS dau_bot, SUM(mau_cal) AS mau_cal, SUM(dau_cal) AS dau_cal, 1 AS ord FROM t
  UNION ALL SELECT '2. указали ник', COUNT(*), SUM(mau_bot), SUM(dau_bot), SUM(mau_cal), SUM(dau_cal), 2 FROM t WHERE has_nick = 1
  UNION ALL SELECT '3. прошли регистрацию (подписка на канал)', COUNT(*), SUM(mau_bot), SUM(dau_bot), SUM(mau_cal), SUM(dau_cal), 3 FROM t WHERE reg = 1
  UNION ALL SELECT '4. выполнили хотя бы 1 квест', COUNT(*), SUM(mau_bot), SUM(dau_bot), SUM(mau_cal), SUM(dau_cal), 4 FROM t WHERE quester = 1
  UNION ALL SELECT '5. аккаунту больше 7 дней', COUNT(*), SUM(mau_bot), SUM(dau_bot), SUM(mau_cal), SUM(dau_cal), 5 FROM t WHERE older7 = 1
  UNION ALL SELECT '6. ник + аккаунту больше 7 дней', COUNT(*), SUM(mau_bot), SUM(dau_bot), SUM(mau_cal), SUM(dau_cal), 6 FROM t WHERE older7 = 1 AND has_nick = 1
) x
ORDER BY ord;

-- Из чего состоит «активный за 30 дней» (по самому «сильному» признаку):
SELECT CASE WHEN u.completed_quests > 0 THEN 'делал квесты'
            WHEN u.registration_completed = TRUE THEN 'зарегистрирован, квестов нет'
            WHEN u.nickname IS NOT NULL AND u.nickname <> '' THEN 'только ник'
            ELSE 'только /start (без ника)' END AS who,
       COUNT(*) AS active_30d
FROM app_users u
WHERE u.last_activity_date >= CAST(DATEADD('DAY', -30, CURRENT_TIMESTAMP) AS DATE)
   OR u.last_bot_activity_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)
   OR u.last_mini_app_open_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)
GROUP BY 1
ORDER BY active_30d DESC;

-- Сколько вообще нужно «доверия» полям активности: у скольких активных нет НИ ОДНОГО из трёх признаков за 30 дней нельзя проверить,
-- но можно посмотреть, сколько аккаунтов, созданных за 30 дней, вообще не имеют отметки активности (по ним MAU не знает про заход):
SELECT COUNT(*) AS created_30d_without_any_activity_mark
FROM app_users u
WHERE u.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)
  AND u.last_activity_date IS NULL AND u.last_bot_activity_at IS NULL AND u.last_mini_app_open_at IS NULL;
