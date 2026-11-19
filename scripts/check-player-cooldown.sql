-- «Долгий кулдаун между квестами» - диагностика по одному игроку. Только чтение.
-- Подставьте @ник игрока без @ вместо __USER__ и запустите на сервере:
--   cd /root/gamebot && git pull origin main && sed 's/__USER__/Resident198/' scripts/check-player-cooldown.sql > /tmp/q.sql && bash scripts/run-sql.sh /tmp/q.sql
-- Кулдаун на квесты игры считается от МАКСИМАЛЬНОЙ даты updated_at среди ОДОБРЕННЫХ заявок игрока по этой игре и категории (+24 ч; у новичка 4 ч, у UGC 12 ч).
-- Если updated_at одобренной заявки сдвигается позже самого одобрения (фоновые задачи, миграции при старте), кулдаун «перезапускается» - сравните created_at, updated_at и разницу.

-- 1) Игрок
SELECT u.id, u.telegram_id, u.nickname, u.completed_quests, CAST(u.last_quest_taken_at AS TIMESTAMP(0)) AS last_quest_taken_at,
       u.cooldown_bypass_game, CAST(CURRENT_TIMESTAMP AS TIMESTAMP(0)) AS now_utc
FROM app_users u WHERE LOWER(u.telegram_username) = LOWER('__USER__');

-- 2) Последние 15 заявок: игра, категория, статус, когда взял и когда заявка менялась в последний раз
SELECT s.id, s.status, q.game_name, q.category, q.title,
       CAST(s.created_at AS TIMESTAMP(0)) AS taken_at, CAST(s.updated_at AS TIMESTAMP(0)) AS updated_at,
       DATEDIFF('HOUR', s.created_at, s.updated_at) AS hours_taken_to_updated
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id JOIN app_users u ON u.id = s.user_id
WHERE LOWER(u.telegram_username) = LOWER('__USER__') ORDER BY s.created_at DESC LIMIT 15;

-- 3) По каждой игре: когда заканчивается кулдаун (последний updated_at одобренной + 24 ч) и сколько часов осталось
SELECT q.game_name, q.category, MAX(s.updated_at) AS last_approved_updated_at,
       DATEADD('HOUR', 24, MAX(s.updated_at)) AS cooldown_until,
       DATEDIFF('HOUR', CURRENT_TIMESTAMP, DATEADD('HOUR', 24, MAX(s.updated_at))) AS hours_left
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id JOIN app_users u ON u.id = s.user_id
WHERE LOWER(u.telegram_username) = LOWER('__USER__') AND s.status = 'APPROVED'
GROUP BY q.game_name, q.category ORDER BY MAX(s.updated_at) DESC;
