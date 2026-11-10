-- Кто потеряет партнёрский статус (курс 100%) после правила «партнёр = квест одобрен И банк выплатил бонус» (10.11.2026). Только чтение.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-partner-status-change.sql
-- 1) игроки с одобренной первой частью партнёрского квеста, у которых бонус банка ещё не отмечен (раньше были партнёрами, теперь ждут выплаты)
SELECT u.telegram_id, u.telegram_username, u.nickname, u.country, u.coins, CAST(s.created_at AS TIMESTAMP(0)) AS taken, CAST(s.updated_at AS TIMESTAMP(0)) AS approved_at
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id JOIN app_users u ON u.id = s.user_id
WHERE q.game_name = 'Банк-партнёр' AND s.status = 'APPROVED' AND s.bonus_paid_at IS NULL ORDER BY s.updated_at;
-- 2) игроки, у которых бонус выплачен (остаются партнёрами)
SELECT u.telegram_id, u.telegram_username, u.nickname, CAST(s.bonus_paid_at AS TIMESTAMP(0)) AS bonus_paid_at
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id JOIN app_users u ON u.id = s.user_id
WHERE q.game_name = 'Банк-партнёр' AND s.status = 'APPROVED' AND s.bonus_paid_at IS NOT NULL ORDER BY s.bonus_paid_at;
