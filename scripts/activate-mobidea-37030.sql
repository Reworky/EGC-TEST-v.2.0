-- Включает квест по офферу Mobidea 37030 (установка приложения, Android) и показывает все квесты Mobidea.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/activate-mobidea-37030.sql
UPDATE quests SET active = TRUE WHERE external_network = 'mobidea' AND external_offer_id = '37030';
SELECT id, title, game_name, active, participant_limit, reward_coins, external_offer_id FROM quests WHERE external_network = 'mobidea';
