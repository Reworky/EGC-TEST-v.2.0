-- ОТКАТ «Заданий дня» без деплоя: кнопка пропадает из меню, сундук заданий не выдаётся. Меняет данные (одна строка в app_settings).
-- Запуск на сервере: cd /root/gamebot && bash scripts/run-sql.sh scripts/daily-tasks-off.sql
-- Включить обратно: DELETE FROM app_settings WHERE setting_key = 'daily_tasks.enabled';
-- Изменить суточный потолок выдачи (по умолчанию 15000 EXC): MERGE INTO app_settings (setting_key, setting_value) KEY (setting_key) VALUES ('daily_tasks.cap_exc', '20000');
MERGE INTO app_settings (setting_key, setting_value) KEY (setting_key) VALUES ('daily_tasks.enabled', 'false');
SELECT setting_key, setting_value FROM app_settings WHERE setting_key LIKE 'daily_tasks.%';
