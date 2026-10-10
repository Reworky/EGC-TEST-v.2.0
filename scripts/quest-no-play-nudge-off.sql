-- ОТКАТ напоминания «взял квест, но не играл» без деплоя. Меняет данные (одна строка в app_settings).
-- Запуск на сервере: cd /root/gamebot && bash scripts/run-sql.sh scripts/quest-no-play-nudge-off.sql
-- Включить обратно: DELETE FROM app_settings WHERE setting_key = 'quest.no_play_nudge.enabled';
MERGE INTO app_settings (setting_key, setting_value) KEY (setting_key) VALUES ('quest.no_play_nudge.enabled', 'false');
SELECT setting_key, setting_value FROM app_settings WHERE setting_key = 'quest.no_play_nudge.enabled';
