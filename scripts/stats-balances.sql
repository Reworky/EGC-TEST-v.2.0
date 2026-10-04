-- Распределение баланса EXC по давности активности игроков (для оценки «мёртвых» балансов).
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-balances.sql
SELECT CASE
         WHEN last_activity_date IS NULL THEN '0. нет активности'
         WHEN DATEDIFF('DAY', last_activity_date, CURRENT_DATE) <= 7 THEN '1. активен до 7 дней'
         WHEN DATEDIFF('DAY', last_activity_date, CURRENT_DATE) <= 30 THEN '2. 8-30 дней'
         WHEN DATEDIFF('DAY', last_activity_date, CURRENT_DATE) <= 60 THEN '3. 31-60 дней'
         WHEN DATEDIFF('DAY', last_activity_date, CURRENT_DATE) <= 90 THEN '4. 61-90 дней'
         ELSE '5. больше 90 дней'
       END AS bucket,
       COUNT(*) AS players,
       SUM(coins) AS total_exc,
       SUM(CASE WHEN coins >= 5000 THEN 1 ELSE 0 END) AS players_5000_plus,
       SUM(CASE WHEN coins >= 5000 THEN coins ELSE 0 END) AS exc_in_5000_plus
FROM app_users
WHERE registration_completed = TRUE
GROUP BY 1
ORDER BY 1;
