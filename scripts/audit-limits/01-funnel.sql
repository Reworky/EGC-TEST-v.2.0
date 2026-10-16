-- 1) Воронка жизни игрока: где люди отваливаются на пути к выводу (все активированные, не заблокированные).
-- «Заработали» = сумма всех положительных начислений EXC (квесты, бонусы, рефералы), с учётом возвратов - это приближение.
WITH u AS (SELECT id, completed_quests FROM app_users WHERE registration_completed = TRUE AND blocked = FALSE),
earn AS (SELECT user_id, SUM(amount) AS earned FROM exc_transactions WHERE amount > 0 GROUP BY user_id),
wd AS (SELECT rr.user_id, COUNT(*) AS requested, SUM(CASE WHEN rr.status = 'APPROVED' THEN 1 ELSE 0 END) AS paid
       FROM reward_requests rr JOIN reward_items ri ON ri.id = rr.reward_id WHERE ri.category = 'Вывод' GROUP BY rr.user_id)
SELECT step, cnt, ROUND(100.0 * cnt / (SELECT COUNT(*) FROM u), 1) AS pct_of_activated FROM (
  SELECT 1 AS n, 'Активировали аккаунт' AS step, COUNT(*) AS cnt FROM u
  UNION ALL SELECT 2, '>=1 одобренный квест', COUNT(*) FROM u WHERE completed_quests >= 1
  UNION ALL SELECT 3, '>=3 квестов (порог открытия вывода)', COUNT(*) FROM u WHERE completed_quests >= 3
  UNION ALL SELECT 4, '>=10 квестов', COUNT(*) FROM u WHERE completed_quests >= 10
  UNION ALL SELECT 5, 'Заработали >= 5 000 EXC (минимум вывода)', COUNT(*) FROM u JOIN earn e ON e.user_id = u.id WHERE e.earned >= 5000
  UNION ALL SELECT 6, 'Подали заявку на вывод', COUNT(*) FROM u JOIN wd ON wd.user_id = u.id WHERE wd.requested >= 1
  UNION ALL SELECT 7, 'Получили >= 1 выплату', COUNT(*) FROM u JOIN wd ON wd.user_id = u.id WHERE wd.paid >= 1
  UNION ALL SELECT 8, 'Получили >= 2 выплат', COUNT(*) FROM u JOIN wd ON wd.user_id = u.id WHERE wd.paid >= 2
  UNION ALL SELECT 9, 'Получили >= 5 выплат', COUNT(*) FROM u JOIN wd ON wd.user_id = u.id WHERE wd.paid >= 5
) t ORDER BY n;
