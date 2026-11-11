-- Банковский квест, нулевой шаг: кто берёт квест и не доходит до заказа карты. Только чтение.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-bank-quest-step0.sql
-- 1) все заявки на банковский квест: кто (возраст, страна), как долго жила, отменил сам или закрыла автоматика, нажал ли «Начать»
SELECT s.id, s.status, s.mission_step AS step, u.age, u.country, u.completed_quests AS quests,
       s.created_at AS taken_at,
       ROUND(DATEDIFF('MINUTE', s.created_at, s.updated_at) / 60.0, 1) AS hours_alive,
       CASE WHEN s.auto_closed_at IS NOT NULL THEN 'автозакрытие' ELSE 'вручную/нет' END AS closed_by,
       CASE WHEN EXISTS (SELECT 1 FROM partner_quest_events e WHERE e.telegram_id = u.telegram_id AND e.type = 'START_CLICKED') THEN 'да' ELSE 'нет' END AS clicked_start,
       s.card_activated_on AS activated
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id JOIN app_users u ON u.id = s.user_id
WHERE q.game_name IN ('Банк-партнёр', 'ОТП Банк') ORDER BY s.created_at;
-- 2) сколько из взявших квест не подходит по условиям банка (18+ и РФ) или возраст неизвестен
SELECT CASE WHEN u.age IS NULL THEN 'возраст неизвестен' WHEN u.age < 18 THEN 'младше 18' ELSE '18+' END AS age_group,
       CASE WHEN LOWER(COALESCE(u.country, '')) IN ('россия', 'russia', 'рф', 'ru') THEN 'Россия' WHEN u.country IS NULL OR u.country = '' THEN 'страна неизвестна' ELSE 'другая страна' END AS country_group,
       COUNT(DISTINCT u.id) AS players,
       SUM(CASE WHEN s.status = 'CANCELLED' AND s.mission_step <= 1 THEN 1 ELSE 0 END) AS cancelled_at_start,
       SUM(CASE WHEN s.card_activated_on IS NOT NULL THEN 1 ELSE 0 END) AS activated
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id JOIN app_users u ON u.id = s.user_id
WHERE q.game_name IN ('Банк-партнёр', 'ОТП Банк') GROUP BY 1, 2 ORDER BY 1, 2;
