-- ВЫКЛЮЧАЕТ рассылку игрокам о спонсорских квестах (пуш при создании и напоминание через сутки). Уже отправленное не отзывается.
-- Запуск на сервере: cd /root/gamebot && bash scripts/run-sql.sh scripts/sponsor-push-off.sql
MERGE INTO app_settings (setting_key, setting_value) KEY (setting_key) VALUES ('sponsor_push.enabled', 'false');
SELECT setting_key, setting_value FROM app_settings WHERE setting_key LIKE 'sponsor_%';
