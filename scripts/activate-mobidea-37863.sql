-- Включает квест по офферу Mobidea 37863 (MegaPrognoze) и показывает результат.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/activate-mobidea-37863.sql
UPDATE quests SET active = TRUE WHERE external_network = 'mobidea' AND external_offer_id = '37863';
SELECT id, title, game_name, active, participant_limit, reward_coins, external_offer_id FROM quests WHERE external_network = 'mobidea';
