-- Кто выводит звёзды (и любые заявки на вывод), у кого мало выполненных квестов. Только читает базу.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/audit-stars-withdrawals-no-quests.sql
-- completed_quests - счётчик, по которому бот пускает к выводу (нужно 3); approved_subs - реально одобренные заявки на квесты.
SELECT r.display_id, r.status, r.created_at, ri.title, u.telegram_id, u.created_at AS registered, u.completed_quests, u.coins, u.withdrawal_frozen,
  (SELECT COUNT(*) FROM quest_submissions s WHERE s.user_id = u.id AND s.status = 'APPROVED') AS approved_subs
FROM reward_requests r
JOIN reward_items ri ON ri.id = r.reward_id
JOIN app_users u ON u.id = r.user_id
WHERE ri.category = 'Вывод' AND r.status <> 'REJECTED' AND r.status <> 'CANCELLED'
  AND (u.completed_quests < 3 OR (SELECT COUNT(*) FROM quest_submissions s WHERE s.user_id = u.id AND s.status = 'APPROVED') < 3)
ORDER BY r.created_at DESC LIMIT 30;
