-- Какие предметы из «Предметов клуба» покупают чаще всего сейчас (для призов слота и колеса за EXC). Только чтение.
-- Запуск: cd /root/gamebot && git pull origin main && bash scripts/run-sql.sh scripts/check-sink-popularity.sql
-- Берутся списания типа SINK; слот и колесо за EXC (описания «Слот» и «Колесо за EXC») и отключённый реролл в рейтинг не входят.
-- Колонки: покупок и покупателей за 90, 30 и 7 дней и сколько EXC потрачено за 90 дней. Смотреть в первую очередь 30 дней: это текущая популярность.
SELECT x.item,
       COUNT(*) AS purchases_90d, COUNT(DISTINCT x.user_id) AS buyers_90d,
       SUM(x.d30) AS purchases_30d, COUNT(DISTINCT CASE WHEN x.d30 = 1 THEN x.user_id END) AS buyers_30d,
       SUM(x.d7) AS purchases_7d,
       -SUM(x.amount) AS exc_spent_90d
FROM (
  SELECT CASE
           WHEN t.description LIKE 'Титул:%' THEN 'Титул'
           WHEN t.description LIKE 'Подарок буст%' THEN 'Подарок буст'
           WHEN t.description LIKE 'Снятие кулдауна%' THEN 'Снятие кулдауна'
           WHEN t.description LIKE 'Дополнительный слот%' THEN 'Дополнительный слот квеста'
           ELSE t.description
         END AS item,
         t.user_id AS user_id, t.amount AS amount,
         CASE WHEN t.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) THEN 1 ELSE 0 END AS d30,
         CASE WHEN t.created_at >= DATEADD('DAY', -7, CURRENT_TIMESTAMP) THEN 1 ELSE 0 END AS d7
  FROM exc_transactions t
  WHERE t.type = 'SINK' AND t.amount < 0 AND t.created_at >= DATEADD('DAY', -90, CURRENT_TIMESTAMP)
    AND t.description NOT LIKE 'Слот%' AND t.description NOT LIKE 'Колесо за EXC%' AND t.description NOT LIKE 'Реролл%'
) x
GROUP BY x.item
ORDER BY purchases_30d DESC, purchases_90d DESC;
