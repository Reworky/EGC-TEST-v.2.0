-- Кому хватит EXC на VPN: баланс против цен 54 000 / 144 000 / 486 000 EXC и скидки 18 000 EXC. Только читает базу.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-vpn-exc-affordability.sql
CREATE LOCAL TEMPORARY TABLE aud AS
SELECT u.coins AS coins,
  GREATEST(COALESCE(u.last_activity_date, DATE '2000-01-01'),
           CAST(COALESCE(u.last_bot_activity_at, TIMESTAMP '2000-01-01 00:00:00') AS DATE),
           CAST(COALESCE(u.last_mini_app_open_at, TIMESTAMP '2000-01-01 00:00:00') AS DATE)) AS last_act
FROM app_users u
WHERE u.registration_completed = TRUE AND COALESCE(u.blocked, FALSE) = FALSE;

SELECT 'активны 30 дн.' AS grp,
  COUNT(*) AS total,
  SUM(CASE WHEN coins >= 18000 THEN 1 ELSE 0 END) AS from_18000_discount,
  SUM(CASE WHEN coins >= 54000 THEN 1 ELSE 0 END) AS from_54000_month,
  SUM(CASE WHEN coins >= 144000 THEN 1 ELSE 0 END) AS from_144000_3months,
  SUM(CASE WHEN coins >= 486000 THEN 1 ELSE 0 END) AS from_486000_year,
  MAX(coins) AS max_balance
FROM aud WHERE last_act >= DATEADD('DAY', -30, CURRENT_DATE)
UNION ALL
SELECT 'все зарегистрированные',
  COUNT(*),
  SUM(CASE WHEN coins >= 18000 THEN 1 ELSE 0 END),
  SUM(CASE WHEN coins >= 54000 THEN 1 ELSE 0 END),
  SUM(CASE WHEN coins >= 144000 THEN 1 ELSE 0 END),
  SUM(CASE WHEN coins >= 486000 THEN 1 ELSE 0 END),
  MAX(coins)
FROM aud;
