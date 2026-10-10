-- ОТКАТ ступеней прогресса автоквестов (сообщения «половина пути» / «последний шаг») без деплоя. Меняет данные (одна строка в app_settings).
-- Запуск на сервере: cd /root/gamebot && bash scripts/run-sql.sh scripts/quest-milestones-off.sql
-- Включить обратно: DELETE FROM app_settings WHERE setting_key = 'quest.progress_milestones.enabled';
MERGE INTO app_settings (setting_key, setting_value) KEY (setting_key) VALUES ('quest.progress_milestones.enabled', 'false');
SELECT setting_key, setting_value FROM app_settings WHERE setting_key = 'quest.progress_milestones.enabled';
