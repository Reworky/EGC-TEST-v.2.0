-- Код дня: как отрабатывает механика (публикуется ли, сколько вводят, как быстро, кто, возвращаются ли на следующий день). Только чтение.
-- Запуск: cd /root/gamebot && git pull origin main && bash scripts/run-sql.sh scripts/check-code-of-day-results.sql
-- Время сервера = UTC. Ввод кода сам засчитывается как активный день, поэтому сравнение «ввёл / не ввёл» (п. 4) показывает связь, а не доказывает причину:
-- код вводят самые вовлечённые. Смотреть динамику и долю от активных.

-- 1) Настройки автопоста (cc.dcode.enabled = 1 включён; hour - час UTC) и последние черновики: не завис ли PENDING
SELECT s.setting_key, s.setting_value FROM app_settings s WHERE s.setting_key LIKE 'cc.dcode.%' ORDER BY s.setting_key;
SELECT d.id, d.status, CAST(d.created_at AS TIMESTAMP(0)) AS created, d.meta AS code FROM channel_post_drafts d WHERE d.type = 'DAILY_CODE' ORDER BY d.id DESC LIMIT 8;

-- 2) Коды за 30 дней: сколько ввели, из них за первый час и за 6 часов, доля от активных за день (DAU по журналу активности)
SELECT CAST(c.published_at AS DATE) AS code_date, c.code, COUNT(cl.id) AS claims,
       SUM(CASE WHEN cl.claimed_at <= DATEADD('HOUR', 1, c.published_at) THEN 1 ELSE 0 END) AS in_first_hour,
       SUM(CASE WHEN cl.claimed_at <= DATEADD('HOUR', 6, c.published_at) THEN 1 ELSE 0 END) AS in_6_hours,
       (SELECT COUNT(*) FROM user_activity_days a WHERE a.activity_day = CAST(c.published_at AS DATE)) AS dau,
       ROUND(100.0 * COUNT(cl.id) / NULLIF((SELECT COUNT(*) FROM user_activity_days a WHERE a.activity_day = CAST(c.published_at AS DATE)), 0), 1) AS claims_pct_of_dau
FROM daily_codes c LEFT JOIN daily_code_claims cl ON cl.code_id = c.id
WHERE c.published_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)
GROUP BY c.id, CAST(c.published_at AS DATE), c.code ORDER BY c.published_at DESC;

-- 3) Серии: сколько игроков вводили код в N разных днях за последние 30 дней
SELECT t.n_days, COUNT(*) AS players FROM (
  SELECT cl.user_id, COUNT(DISTINCT cl.claim_date) AS n_days FROM daily_code_claims cl
  WHERE cl.claim_date >= DATEADD('DAY', -30, CURRENT_DATE) GROUP BY cl.user_id
) t GROUP BY t.n_days ORDER BY t.n_days;

-- 4) Возврат на следующий день: среди активных в день кода - ввёл код / не ввёл, и сколько из них были активны на следующий день
SELECT x.code_date, x.claimed, COUNT(*) AS players, SUM(x.next_day) AS active_next_day,
       ROUND(100.0 * SUM(x.next_day) / COUNT(*), 1) AS next_day_pct
FROM (
  SELECT a.activity_day AS code_date, a.user_id,
         CASE WHEN EXISTS (SELECT 1 FROM daily_code_claims cl WHERE cl.user_id = a.user_id AND cl.claim_date = a.activity_day) THEN '1 ввёл код' ELSE '2 не ввёл' END AS claimed,
         CASE WHEN EXISTS (SELECT 1 FROM user_activity_days b WHERE b.user_id = a.user_id AND b.activity_day = DATEADD('DAY', 1, a.activity_day)) THEN 1 ELSE 0 END AS next_day
  FROM user_activity_days a
  WHERE a.activity_day IN (SELECT CAST(c.published_at AS DATE) FROM daily_codes c WHERE c.published_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP))
    AND a.activity_day < CURRENT_DATE
) x GROUP BY x.code_date, x.claimed ORDER BY x.code_date DESC, x.claimed;

-- 5) Кто вводит: по давности регистрации (новички или старые игроки), за 30 дней
SELECT t.age_group, COUNT(DISTINCT t.user_id) AS players, COUNT(*) AS claims FROM (
  SELECT cl.user_id,
         CASE WHEN u.created_at >= DATEADD('DAY', -7, CURRENT_TIMESTAMP) THEN '1 до 7 дн.'
              WHEN u.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) THEN '2 7-30 дн.'
              ELSE '3 старше 30 дн.' END AS age_group
  FROM daily_code_claims cl JOIN app_users u ON u.id = cl.user_id
  WHERE cl.claim_date >= DATEADD('DAY', -30, CURRENT_DATE)
) t GROUP BY t.age_group ORDER BY t.age_group;
