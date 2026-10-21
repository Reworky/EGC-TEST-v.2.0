-- Хэллоуин-2026: созданы ли шесть квестов, окна, разовость, настройки события. Только чтение.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-halloween.sql
SELECT id, title, game_name, active, one_time_per_account AS one_time, reward_coins, reward_xp, available_from, available_until, event_code, pack_id, pack_suspended
FROM quests WHERE event_code = 'halloween2026' ORDER BY available_from, game_name;
SELECT setting_key, setting_value FROM app_settings WHERE setting_key LIKE 'event.%' ORDER BY setting_key;
