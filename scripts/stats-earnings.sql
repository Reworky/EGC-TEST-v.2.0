-- Сколько EXC заработали за последние 30 дней игроки, у которых был хотя бы один зачтённый квест.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-earnings.sql
-- Считаем начисления типов QUEST и BONUS (награда и бонусы за квесты); приветственные, ежедневные и прочие не входят.
SELECT bucket,
       COUNT(*) AS players,
       SUM(earned) AS total_exc,
       ROUND(AVG(earned)) AS avg_exc,
       MIN(earned) AS min_exc,
       MAX(earned) AS max_exc
FROM (
  SELECT earned,
         CASE
           WHEN earned < 2000 THEN '1. до 2 000'
           WHEN earned < 5000 THEN '2. 2 000 - 5 000'
           WHEN earned < 10000 THEN '3. 5 000 - 10 000'
           WHEN earned < 25000 THEN '4. 10 000 - 25 000'
           WHEN earned < 50000 THEN '5. 25 000 - 50 000'
           ELSE '6. больше 50 000'
         END AS bucket
  FROM (
    SELECT user_id, SUM(amount) AS earned
    FROM exc_transactions
    WHERE type IN ('QUEST', 'BONUS') AND amount > 0
      AND created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)
    GROUP BY user_id
  ) u
) t
GROUP BY bucket
ORDER BY bucket;

-- Медиана и 90-й перцентиль (по тем же игрокам).
SELECT MEDIAN(earned) AS median_exc,
       PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY earned) AS p90_exc,
       COUNT(*) AS players
FROM (
  SELECT user_id, SUM(amount) AS earned
  FROM exc_transactions
  WHERE type IN ('QUEST', 'BONUS') AND amount > 0
    AND created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)
  GROUP BY user_id
) u;
