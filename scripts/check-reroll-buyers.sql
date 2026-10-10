-- Кто покупал «Реролл квеста» (2 000 EXC), которого фактически не существовало: покупка только списывала EXC. Только чтение.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-reroll-buyers.sql
SELECT u.telegram_id, u.nickname, COUNT(*) AS purchases, SUM(-t.amount) AS spent_exc, MAX(t.created_at) AS last_purchase
FROM exc_transactions t
JOIN app_users u ON u.id = t.user_id
WHERE t.description = 'Реролл квеста'
GROUP BY u.telegram_id, u.nickname
ORDER BY last_purchase DESC;
