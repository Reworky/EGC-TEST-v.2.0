-- Открывает выдачу кейсов, капсул и скинов CS2. Применяется сразу, без перезапуска.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/cs2-all-open.sql
MERGE INTO app_settings (setting_key, setting_value) KEY (setting_key) VALUES ('cs2.skins.paused', '0');
MERGE INTO app_settings (setting_key, setting_value) KEY (setting_key) VALUES ('cs2.cases.paused', '0');
