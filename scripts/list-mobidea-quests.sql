-- Только чтение: квесты Mobidea (какие созданы, активны ли, какая награда и ссылка).
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/list-mobidea-quests.sql
SELECT id, title, game_name, active, participant_limit, reward_coins, external_offer_id, LEFT(instruction, 140) AS instruction_start
FROM quests WHERE external_network = 'mobidea' ORDER BY id;
