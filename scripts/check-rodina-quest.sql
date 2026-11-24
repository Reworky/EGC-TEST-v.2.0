-- Проверка квеста «Родина РП» после деплоя (только чтение).
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-rodina-quest.sql
-- Ожидаем: строка квеста (active = FALSE до включения) и ключ rodina.card.v2.done.
SELECT id, title, active, game_name, external_network, external_offer_id, reward_coins FROM quests WHERE game_name = 'Родина РП';
SELECT setting_key, setting_value FROM app_settings WHERE setting_key LIKE 'rodina.card.%';
