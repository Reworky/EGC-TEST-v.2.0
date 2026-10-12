-- ВКЛЮЧАЕТ рассылку игрокам о новом спонсорском квесте «подпишись на канал» (пуш при создании + напоминание через сутки).
-- По умолчанию рассылка ВЫКЛЮЧЕНА. Включайте только после одобрения текста. Меняет данные (строка в app_settings).
-- Запуск на сервере: cd /root/gamebot && bash scripts/run-sql.sh scripts/sponsor-push-on.sql ; выключить: scripts/sponsor-push-off.sql
MERGE INTO app_settings (setting_key, setting_value) KEY (setting_key) VALUES ('sponsor_push.enabled', 'true');
SELECT setting_key, setting_value FROM app_settings WHERE setting_key LIKE 'sponsor_%';
