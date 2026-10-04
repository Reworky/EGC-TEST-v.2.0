-- Эмиссия и погашение EXC по месяцам и типам операций (для анализа перехода на звёзды).
-- Запуск на сервере: cd /root/gamebot && bash scripts/run-sql.sh scripts/stats-emission.sql
-- Положительные суммы = выдано игрокам, отрицательные = потрачено/выведено.
SELECT FORMATDATETIME(created_at, 'yyyy-MM') AS ym,
       type,
       COUNT(*) AS ops,
       SUM(CASE WHEN amount > 0 THEN amount ELSE 0 END) AS issued_exc,
       SUM(CASE WHEN amount < 0 THEN -amount ELSE 0 END) AS removed_exc
FROM exc_transactions
GROUP BY FORMATDATETIME(created_at, 'yyyy-MM'), type
ORDER BY ym, type;

-- Итоги по месяцам: всего выдано, всего убрано, активных игроков.
SELECT FORMATDATETIME(created_at, 'yyyy-MM') AS ym,
       SUM(CASE WHEN amount > 0 THEN amount ELSE 0 END) AS issued_total,
       SUM(CASE WHEN amount < 0 THEN -amount ELSE 0 END) AS removed_total,
       COUNT(DISTINCT user_id) AS active_users
FROM exc_transactions
GROUP BY FORMATDATETIME(created_at, 'yyyy-MM')
ORDER BY ym;
