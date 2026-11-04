-- Аудитория рассылки про EGC VPN: из кого состоит база и кому писать. Только читает базу (временная таблица живёт в сессии скрипта).
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-vpn-broadcast-audience.sql
CREATE LOCAL TEMPORARY TABLE aud AS
SELECT u.id, u.telegram_id,
  CASE WHEN u.registration_completed = TRUE THEN 1 ELSE 0 END AS reg,
  CASE WHEN u.blocked = TRUE THEN 1 ELSE 0 END AS banned,
  CASE WHEN u.withdrawal_frozen = TRUE THEN 1 ELSE 0 END AS frozen,
  COALESCE(NULLIF(u.country, ''), 'не указана') AS country,
  COALESCE(u.platforms_csv, '') AS platforms,
  u.completed_quests AS quests,
  u.coins AS coins,
  u.created_at,
  GREATEST(COALESCE(u.last_activity_date, DATE '2000-01-01'),
           CAST(COALESCE(u.last_bot_activity_at, TIMESTAMP '2000-01-01 00:00:00') AS DATE),
           CAST(COALESCE(u.last_mini_app_open_at, TIMESTAMP '2000-01-01 00:00:00') AS DATE)) AS last_act,
  CASE WHEN EXISTS (SELECT 1 FROM vpn_accounts v WHERE v.telegram_id = u.telegram_id) THEN 1 ELSE 0 END AS has_vpn
FROM app_users u;

SELECT '1. всего аккаунтов' AS metric, COUNT(*) AS n FROM aud
UNION ALL SELECT '2. прошли регистрацию', COUNT(*) FROM aud WHERE reg = 1
UNION ALL SELECT '3. забанены (blocked)', COUNT(*) FROM aud WHERE banned = 1
UNION ALL SELECT '4. регистрация, не забанены, активны за 7 дн.', COUNT(*) FROM aud WHERE reg = 1 AND banned = 0 AND last_act >= DATEADD('DAY', -7, CURRENT_DATE)
UNION ALL SELECT '5. то же, за 30 дн.', COUNT(*) FROM aud WHERE reg = 1 AND banned = 0 AND last_act >= DATEADD('DAY', -30, CURRENT_DATE)
UNION ALL SELECT '6. то же, за 90 дн.', COUNT(*) FROM aud WHERE reg = 1 AND banned = 0 AND last_act >= DATEADD('DAY', -90, CURRENT_DATE)
UNION ALL SELECT '7. с пилотным VPN-аккаунтом', COUNT(*) FROM aud WHERE has_vpn = 1
ORDER BY 1;

SELECT 'давность активности (регистрация, не бан)' AS grp, bucket, COUNT(*) AS n FROM (
  SELECT CASE WHEN last_act >= DATEADD('DAY', -1, CURRENT_DATE) THEN 'a. 0-1 дн'
              WHEN last_act >= DATEADD('DAY', -7, CURRENT_DATE) THEN 'b. 2-7 дн'
              WHEN last_act >= DATEADD('DAY', -14, CURRENT_DATE) THEN 'c. 8-14 дн'
              WHEN last_act >= DATEADD('DAY', -30, CURRENT_DATE) THEN 'd. 15-30 дн'
              WHEN last_act >= DATEADD('DAY', -90, CURRENT_DATE) THEN 'e. 31-90 дн'
              ELSE 'f. больше 90 дн' END AS bucket
  FROM aud WHERE reg = 1 AND banned = 0) x GROUP BY bucket ORDER BY bucket;

SELECT 'страна, активны 30 дн.' AS grp, country, COUNT(*) AS n
FROM aud WHERE reg = 1 AND banned = 0 AND last_act >= DATEADD('DAY', -30, CURRENT_DATE)
GROUP BY country ORDER BY n DESC LIMIT 12;

SELECT 'платформы (в списке), активны 30 дн.' AS grp,
  SUM(CASE WHEN UPPER(platforms) LIKE '%IPHONE%' THEN 1 ELSE 0 END) AS iphone,
  SUM(CASE WHEN UPPER(platforms) LIKE '%ANDROID%' THEN 1 ELSE 0 END) AS android,
  SUM(CASE WHEN UPPER(platforms) LIKE '%PC%' THEN 1 ELSE 0 END) AS pc,
  SUM(CASE WHEN platforms = '' THEN 1 ELSE 0 END) AS not_set
FROM aud WHERE reg = 1 AND banned = 0 AND last_act >= DATEADD('DAY', -30, CURRENT_DATE);

SELECT 'по числу квестов, активны 30 дн.' AS grp, bucket, COUNT(*) AS n FROM (
  SELECT CASE WHEN quests = 0 THEN 'a. 0' WHEN quests <= 2 THEN 'b. 1-2' WHEN quests <= 9 THEN 'c. 3-9' WHEN quests <= 29 THEN 'd. 10-29' ELSE 'e. 30+' END AS bucket
  FROM aud WHERE reg = 1 AND banned = 0 AND last_act >= DATEADD('DAY', -30, CURRENT_DATE)) x GROUP BY bucket ORDER BY bucket;

SELECT 'EXC на балансе, активны 30 дн. (кому подойдёт скидка)' AS grp,
  SUM(CASE WHEN coins >= 18000 THEN 1 ELSE 0 END) AS enough_18000,
  SUM(CASE WHEN coins >= 9000 AND coins < 18000 THEN 1 ELSE 0 END) AS from_9000,
  SUM(CASE WHEN coins < 9000 THEN 1 ELSE 0 END) AS under_9000
FROM aud WHERE reg = 1 AND banned = 0 AND last_act >= DATEADD('DAY', -30, CURRENT_DATE);
