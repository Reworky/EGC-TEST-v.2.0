-- Почему отменённый квест снова «В процессе»: все заявки игрока по квесту и все его открытые заявки. Только чтение.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-user-quest-submissions.sql
-- Поменяйте число ниже на Telegram ID тестового аккаунта (по нику искать нельзя: похожие ники дают чужие строки).
-- 1) Квесты с таким названием (нет ли дублей, например «Brawl Stars» и «BRAWL STARS»)
SELECT id, title, game_name, active, platform FROM quests WHERE LOWER(title) LIKE 'повысь уровень профиля%' ORDER BY id;
-- 2) Все заявки игрока по этим квестам, новые сверху
SELECT s.id, s.display_id, s.status, s.created_at, s.updated_at, s.expires_at, q.id AS quest_id, q.title, q.game_name
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id JOIN app_users u ON u.id = s.user_id
WHERE u.telegram_id = 0 AND LOWER(q.title) LIKE 'повысь уровень профиля%' ORDER BY s.created_at DESC;
-- 3) Все открытые заявки игрока (DRAFT / PENDING / NEEDS_INFO)
SELECT s.id, s.status, s.created_at, s.expires_at, q.title, q.game_name, q.active
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id JOIN app_users u ON u.id = s.user_id
WHERE u.telegram_id = 0 AND s.status IN ('DRAFT', 'PENDING', 'NEEDS_INFO') ORDER BY s.created_at DESC;
