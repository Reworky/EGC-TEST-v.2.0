-- Что у игрока по квестам: все его заявки со статусами (для жалоб «не могу отменить квест», «квест не считается»). Только чтение.
-- Замените 8866769565 на Telegram ID игрока. Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-player-submissions.sql
SELECT u.id AS user_id, u.nickname, u.blocked, u.fraud_suspect, u.withdrawal_frozen, u.completed_quests FROM app_users u WHERE u.telegram_id = 8866769565;

SELECT s.id, s.status, s.created_at AS taken_at, s.updated_at, s.expires_at, q.id AS quest_id, q.game_name, q.title, q.sponsored, q.external_auto_approve
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id JOIN app_users u ON u.id = s.user_id
WHERE u.telegram_id = 8866769565 AND s.status IN ('DRAFT', 'PENDING', 'NEEDS_INFO', 'REJECTED')
ORDER BY s.id DESC;
