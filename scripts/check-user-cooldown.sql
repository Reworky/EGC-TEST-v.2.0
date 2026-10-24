-- Диагностика снятия кулдауна у одного игрока (только чтение). Запуск: bash scripts/run-sql.sh scripts/check-user-cooldown.sql
-- Игрок задаётся в первой строке: telegram_username без @ (регистр не важен).
-- 1) Состояние: флаг снятия, счётчик покупок за день, последнее взятие квеста (лимит «1 квест в час»)
SELECT id, telegram_id, telegram_username, nickname, coins, cooldown_bypass_game, daily_cooldown_removals, daily_cooldown_date,
       last_quest_taken_at, egc_pass_active_until, quest_slot_extra_until, permanent_extra_slot, completed_quests
FROM app_users WHERE LOWER(telegram_username) = 'lrgoal';

-- 2) Покупки/возвраты снятия кулдауна и последние операции EXC
SELECT t.created_at, t.type, t.amount, t.balance_after, t.description
FROM exc_transactions t JOIN app_users u ON u.id = t.user_id
WHERE LOWER(u.telegram_username) = 'lrgoal' AND (LOWER(t.description) LIKE '%кулдаун%' OR t.created_at > DATEADD('DAY', -3, CURRENT_TIMESTAMP))
ORDER BY t.created_at DESC LIMIT 30;

-- 3) Последние заявки на квесты: когда взял/одобрено и по какой игре (что за кулдаун мог упереться)
SELECT s.created_at, s.updated_at, s.status, q.title, q.game_name, q.category
FROM quest_submissions s JOIN app_users u ON u.id = s.user_id JOIN quests q ON q.id = s.quest_id
WHERE LOWER(u.telegram_username) = 'lrgoal'
ORDER BY s.created_at DESC LIMIT 15;
