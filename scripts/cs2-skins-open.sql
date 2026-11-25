-- Открывает выдачу скинов CS2 после техработ. Применяется сразу, без перезапуска.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/cs2-skins-open.sql
MERGE INTO app_settings (setting_key, setting_value) KEY (setting_key) VALUES ('cs2.skins.paused', '0');
