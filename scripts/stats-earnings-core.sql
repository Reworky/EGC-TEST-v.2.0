-- Сколько EXC реально зарабатывают активные игроки за 30 дней (для расчёта цен и лимита трат магазина). Только читает базу.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-earnings-core.sql
-- earned_30 = положительные начисления за 30 дней без возвратов магазина и приветственного бонуса; earn_days_30 = в скольких разных днях были начисления.
CREATE LOCAL TEMPORARY TABLE earn AS
SELECT u.id AS uid,
  DATEDIFF('DAY', CAST(u.created_at AS DATE), CURRENT_DATE) AS age_days,
  COALESCE(SUM(CASE WHEN t.amount > 0 AND t.type NOT IN ('SHOP_REFUND', 'WELCOME_BONUS') THEN t.amount ELSE 0 END), 0) AS earned_30,
  COUNT(DISTINCT CASE WHEN t.amount > 0 AND t.type NOT IN ('SHOP_REFUND', 'WELCOME_BONUS') THEN CAST(t.created_at AS DATE) END) AS earn_days_30,
  COALESCE(SUM(CASE WHEN t.type = 'WITHDRAWAL' THEN -t.amount ELSE 0 END), 0) AS withdrawn_30,
  COALESCE(SUM(CASE WHEN t.type = 'SHOP_BUY' THEN -t.amount ELSE 0 END), 0) AS shop_30
FROM app_users u
LEFT JOIN exc_transactions t ON t.user_id = u.id AND t.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)
WHERE u.registration_completed = TRUE AND COALESCE(u.blocked, FALSE) = FALSE
GROUP BY u.id, u.created_at;

-- Когорты: сколько зарабатывает типичный игрок и верхние процентили (EXC за 30 дней)
SELECT 'все, у кого были начисления' AS cohort, COUNT(*) AS players, ROUND(AVG(earned_30)) AS avg_earned,
  ROUND(PERCENTILE_CONT(0.25) WITHIN GROUP (ORDER BY earned_30)) AS p25, ROUND(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY earned_30)) AS median,
  ROUND(PERCENTILE_CONT(0.75) WITHIN GROUP (ORDER BY earned_30)) AS p75, ROUND(PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY earned_30)) AS p90,
  ROUND(AVG(withdrawn_30)) AS avg_withdrawn, ROUND(AVG(shop_30)) AS avg_shop
FROM earn WHERE earned_30 > 0
UNION ALL
SELECT 'регулярные: начисления >= 5 разных дней', COUNT(*), ROUND(AVG(earned_30)),
  ROUND(PERCENTILE_CONT(0.25) WITHIN GROUP (ORDER BY earned_30)), ROUND(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY earned_30)),
  ROUND(PERCENTILE_CONT(0.75) WITHIN GROUP (ORDER BY earned_30)), ROUND(PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY earned_30)),
  ROUND(AVG(withdrawn_30)), ROUND(AVG(shop_30))
FROM earn WHERE earn_days_30 >= 5
UNION ALL
SELECT 'ядро: в проекте >= 30 дн. и начисления >= 10 разных дней', COUNT(*), ROUND(AVG(earned_30)),
  ROUND(PERCENTILE_CONT(0.25) WITHIN GROUP (ORDER BY earned_30)), ROUND(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY earned_30)),
  ROUND(PERCENTILE_CONT(0.75) WITHIN GROUP (ORDER BY earned_30)), ROUND(PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY earned_30)),
  ROUND(AVG(withdrawn_30)), ROUND(AVG(shop_30))
FROM earn WHERE age_days >= 30 AND earn_days_30 >= 10;

-- Из чего ядро зарабатывает: сумма начислений по типам за 30 дней на одного игрока ядра
SELECT t.type, COUNT(DISTINCT t.user_id) AS users, SUM(t.amount) AS total_exc,
  ROUND(SUM(t.amount) / (SELECT COUNT(*) FROM earn WHERE age_days >= 30 AND earn_days_30 >= 10)) AS per_core_player
FROM exc_transactions t JOIN earn e ON e.uid = t.user_id AND e.age_days >= 30 AND e.earn_days_30 >= 10
WHERE t.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) AND t.amount > 0 AND t.type NOT IN ('SHOP_REFUND', 'WELCOME_BONUS')
GROUP BY t.type ORDER BY total_exc DESC;
