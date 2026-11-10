-- Какие награды и сроки сейчас у квестов CS2 (режим FLAT: единая награда, без лёгких/средних/сложных) - чтобы новая пачка встала на те же значения. Только чтение.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-cs2-rewards.sql
-- 1) квесты CS2: награда, XP, срок, активен ли
SELECT q.title, q.reward_coins, q.reward_xp, q.duration_days, q.active, q.category
FROM quests q WHERE q.game_name = 'CS2' ORDER BY q.active DESC, q.reward_coins, q.title;
-- 2) режим игры в каталоге (FLAT и единая награда)
SELECT g.game_name, g.difficulty_mode, g.flat_reward_exc, g.flat_reward_xp FROM game_catalog g WHERE LOWER(g.game_name) = 'cs2';
