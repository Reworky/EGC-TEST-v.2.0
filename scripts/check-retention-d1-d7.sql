-- Независимая сверка D1/D7 для вкладки «Аналитика → 🔄 Удержание» (критерий приёмки ТЗ 15.10). Только чтение.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-retention-d1-d7.sql
-- Определения те же, что в RetentionService: день 0 = день регистрации по Москве (created_at + 3 ч, сервер в UTC), D1 = активность ровно в день 0+1,
-- D7 = активность ровно в день 0+7, в расчёт входят только игроки с днём 0 внутри журнала, день N считается только когда он строго раньше сегодняшнего дня по Москве.
-- Отличие от вкладки: там недельная когорта попадает в DN целиком, когда день N прошёл у ПОСЛЕДНЕГО зарегистрировавшегося; здесь - по дням регистрации,
-- поэтому недельные суммы ниже могут чуть отличаться от вкладки (по дням сверять точнее: цифры одного дня должны совпасть).

-- 1) Журнал: строк в день ≈ DAU (если пусто или единицы - журнал не пишется)
SELECT activity_day, COUNT(*) AS active_players FROM user_activity_days GROUP BY activity_day ORDER BY activity_day;

-- 2) Когорты по дню регистрации: размер, вернулись на D1 и D7 (NULL = день ещё не прошёл)
SELECT x.day0,
       COUNT(*) AS cohort_size,
       CASE WHEN DATEADD('DAY', 1, x.day0) < CAST(DATEADD('HOUR', 3, CURRENT_TIMESTAMP) AS DATE) THEN SUM(x.d1) END AS d1_returned,
       CASE WHEN DATEADD('DAY', 1, x.day0) < CAST(DATEADD('HOUR', 3, CURRENT_TIMESTAMP) AS DATE) THEN ROUND(100.0 * SUM(x.d1) / COUNT(*), 1) END AS d1_pct,
       CASE WHEN DATEADD('DAY', 7, x.day0) < CAST(DATEADD('HOUR', 3, CURRENT_TIMESTAMP) AS DATE) THEN SUM(x.d7) END AS d7_returned,
       CASE WHEN DATEADD('DAY', 7, x.day0) < CAST(DATEADD('HOUR', 3, CURRENT_TIMESTAMP) AS DATE) THEN ROUND(100.0 * SUM(x.d7) / COUNT(*), 1) END AS d7_pct
FROM (
  SELECT u.id,
         CAST(DATEADD('HOUR', 3, u.created_at) AS DATE) AS day0,
         CASE WHEN EXISTS (SELECT 1 FROM user_activity_days a WHERE a.user_id = u.id
                           AND a.activity_day = DATEADD('DAY', 1, CAST(DATEADD('HOUR', 3, u.created_at) AS DATE))) THEN 1 ELSE 0 END AS d1,
         CASE WHEN EXISTS (SELECT 1 FROM user_activity_days a WHERE a.user_id = u.id
                           AND a.activity_day = DATEADD('DAY', 7, CAST(DATEADD('HOUR', 3, u.created_at) AS DATE))) THEN 1 ELSE 0 END AS d7
  FROM app_users u
  WHERE u.registration_completed = TRUE
    AND CAST(DATEADD('HOUR', 3, u.created_at) AS DATE) >= (SELECT MIN(activity_day) FROM user_activity_days)
) x
GROUP BY x.day0 ORDER BY x.day0;

-- 3) Итого по зрелым дням: D1 и D7 одной строкой (выборка своя у каждого показателя)
SELECT
  SUM(CASE WHEN DATEADD('DAY', 1, x.day0) < CAST(DATEADD('HOUR', 3, CURRENT_TIMESTAMP) AS DATE) THEN 1 ELSE 0 END) AS d1_sample,
  SUM(CASE WHEN DATEADD('DAY', 1, x.day0) < CAST(DATEADD('HOUR', 3, CURRENT_TIMESTAMP) AS DATE) THEN x.d1 ELSE 0 END) AS d1_returned,
  SUM(CASE WHEN DATEADD('DAY', 7, x.day0) < CAST(DATEADD('HOUR', 3, CURRENT_TIMESTAMP) AS DATE) THEN 1 ELSE 0 END) AS d7_sample,
  SUM(CASE WHEN DATEADD('DAY', 7, x.day0) < CAST(DATEADD('HOUR', 3, CURRENT_TIMESTAMP) AS DATE) THEN x.d7 ELSE 0 END) AS d7_returned
FROM (
  SELECT CAST(DATEADD('HOUR', 3, u.created_at) AS DATE) AS day0,
         CASE WHEN EXISTS (SELECT 1 FROM user_activity_days a WHERE a.user_id = u.id
                           AND a.activity_day = DATEADD('DAY', 1, CAST(DATEADD('HOUR', 3, u.created_at) AS DATE))) THEN 1 ELSE 0 END AS d1,
         CASE WHEN EXISTS (SELECT 1 FROM user_activity_days a WHERE a.user_id = u.id
                           AND a.activity_day = DATEADD('DAY', 7, CAST(DATEADD('HOUR', 3, u.created_at) AS DATE))) THEN 1 ELSE 0 END AS d7
  FROM app_users u
  WHERE u.registration_completed = TRUE
    AND CAST(DATEADD('HOUR', 3, u.created_at) AS DATE) >= (SELECT MIN(activity_day) FROM user_activity_days)
) x;

-- 4) Заполненность источника у игроков внутри журнала (доля «неизвестно» в разрезе «Источник»)
SELECT COALESCE(u.traffic_source_code, '(пусто)') AS source, COUNT(*) AS players
FROM app_users u
WHERE u.registration_completed = TRUE
  AND CAST(DATEADD('HOUR', 3, u.created_at) AS DATE) >= (SELECT MIN(activity_day) FROM user_activity_days)
GROUP BY COALESCE(u.traffic_source_code, '(пусто)') ORDER BY players DESC;
