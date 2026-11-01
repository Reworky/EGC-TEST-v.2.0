-- Отклик на партнёрский квест «ОТП Карта» (game_name = 'ОТП Банк'): сколько позвали, сколько взяло, докуда дошли, сколько одобрено. Только чтение.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-otp-card.sql

-- 1) Квесты группы «ОТП Банк» (включая старые версии названия): активен ли, когда создан.
SELECT q.id, q.title, q.active
FROM quests q
WHERE q.game_name = 'ОТП Банк';

-- 2) Сколько игроков получили приглашение /otppilot (в app_settings хранится список telegram id через запятую).
SELECT CASE WHEN s.setting_value IS NULL OR s.setting_value = '' THEN 0
            ELSE LENGTH(s.setting_value) - LENGTH(REPLACE(s.setting_value, ',', '')) + 1 END AS invited
FROM app_settings s
WHERE s.setting_key = 'otp.pilot.sent';

-- 3) Воронка по статусам: сколько взяло квест (любая запись), сколько отправило скриншоты, одобрено, отклонено.
SELECT s.status, COUNT(*) AS submissions, COUNT(DISTINCT s.user_id) AS players
FROM quest_submissions s
JOIN quests q ON q.id = s.quest_id
WHERE q.game_name = 'ОТП Банк'
GROUP BY s.status
ORDER BY submissions DESC;

-- 4) Докуда дошли те, кто ещё не отправил на проверку (шаг миссии 0/1 - первый, дальше по порядку).
SELECT s.mission_step, COUNT(*) AS players
FROM quest_submissions s
JOIN quests q ON q.id = s.quest_id
WHERE q.game_name = 'ОТП Банк' AND s.status = 'DRAFT'
GROUP BY s.mission_step
ORDER BY s.mission_step;

-- 5) По дням: сколько взяло квест (создана запись).
SELECT CAST(s.created_at AS DATE) AS taken_on, COUNT(*) AS taken, COUNT(DISTINCT s.user_id) AS players
FROM quest_submissions s
JOIN quests q ON q.id = s.quest_id
WHERE q.game_name = 'ОТП Банк'
GROUP BY CAST(s.created_at AS DATE)
ORDER BY taken_on;

-- 6) Список заявок: кто, статус, шаг, когда, комментарий модератора (для разбора отказов).
SELECT s.id, u.nickname, u.telegram_username, s.status, s.mission_step, s.created_at, s.updated_at, s.moderator_comment
FROM quest_submissions s
JOIN quests q ON q.id = s.quest_id
JOIN app_users u ON u.id = s.user_id
WHERE q.game_name = 'ОТП Банк'
ORDER BY s.created_at;
