-- Сколько игроков по играм (только чтение). Серверное время UTC.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/players-by-game.sql

-- 1) Привязали аккаунт игры (тег / ID сохранён в профиле)
SELECT 'Brawl Stars' AS game, COUNT(*) AS linked FROM app_users WHERE brawl_stars_tag IS NOT NULL AND brawl_stars_tag <> ''
UNION ALL SELECT 'Clash Royale', COUNT(*) FROM app_users WHERE clash_royale_tag IS NOT NULL AND clash_royale_tag <> ''
UNION ALL SELECT 'Clash of Clans', COUNT(*) FROM app_users WHERE clash_of_clans_tag IS NOT NULL AND clash_of_clans_tag <> ''
UNION ALL SELECT 'CS2', COUNT(*) FROM app_users WHERE cs2_steam_id64 IS NOT NULL
UNION ALL SELECT 'Dota 2', COUNT(*) FROM app_users WHERE dota_account_id IS NOT NULL;

-- 2) Реально играли в клубе: уникальные игроки, взявшие квест по игре (всего / за 30 дней), и с одобренным квестом (всего)
SELECT q.game_name AS game,
       COUNT(DISTINCT s.user_id) AS took_quest_ever,
       COUNT(DISTINCT CASE WHEN s.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) THEN s.user_id END) AS took_quest_30d,
       COUNT(DISTINCT CASE WHEN s.status = 'APPROVED' THEN s.user_id END) AS approved_ever
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
WHERE LOWER(q.game_name) IN ('brawl stars', 'clash royale', 'clash of clans', 'cs2', 'dota 2')
GROUP BY q.game_name ORDER BY took_quest_ever DESC;
