-- Включает все квесты Mobidea (37030, 37863, 37909, 37862) и показывает результат.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/activate-mobidea-all.sql
UPDATE quests SET active = TRUE WHERE external_network = 'mobidea' AND external_offer_id IN ('37030', '37863', '37909', '37862');
SELECT id, title, active, participant_limit, reward_coins, external_offer_id FROM quests WHERE external_network = 'mobidea' ORDER BY id;
