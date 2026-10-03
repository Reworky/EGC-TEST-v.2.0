-- Откуда у трёх связанных аккаунтов столько EXC (только чтение). Запуск: bash scripts/run-sql.sh scripts/audit-withdrawals-farm.sql
-- 1) Все движения EXC по трём аккаунтам (тип, сумма, описание)
SELECT u.telegram_id, u.nickname, t.created_at, t.type, t.amount, t.balance_after, t.description
FROM exc_transactions t
JOIN app_users u ON u.id = t.user_id
WHERE u.telegram_id IN (6317069039, 8925600188, 8532919368)
ORDER BY u.telegram_id, t.created_at;

-- 2) Их заявки на квесты: что брали и что одобрено
SELECT u.telegram_id, u.nickname, s.id AS sub, q.game_name, q.title, q.reward_coins, s.status, s.created_at, s.updated_at
FROM quest_submissions s
JOIN quests q ON q.id = s.quest_id
JOIN app_users u ON u.id = s.user_id
WHERE u.telegram_id IN (6317069039, 8925600188, 8532919368)
ORDER BY u.telegram_id, s.created_at;
