-- Спонсорские кампании: активна ли, бюджет/выдано, лимит подписчиков и сколько уже одобрено. Только чтение.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-sponsor-campaigns.sql
SELECT s.id AS sponsor_id, s.name, s.active AS sponsor_active, s.paid_rub, s.budget_exc, s.spent_exc,
       q.id AS quest_id, q.active AS quest_active, q.participant_limit, q.reward_coins,
       (SELECT COUNT(*) FROM quest_submissions qs WHERE qs.quest_id = q.id AND qs.status = 'APPROVED') AS approved,
       (SELECT COUNT(*) FROM quest_submissions qs WHERE qs.quest_id = q.id AND qs.status = 'DRAFT') AS in_progress
FROM sponsors s
LEFT JOIN quests q ON q.sponsor_id = s.id
ORDER BY s.id DESC;
