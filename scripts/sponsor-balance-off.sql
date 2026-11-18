-- ВЫКЛЮЧИТЬ спонсорский баланс: новые награды партнёрских квестов снова идут на игровой баланс. Уже накопленные спонсорские токены остаются в базе, но экран баланса и вывод их не показывают.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/sponsor-balance-off.sql
MERGE INTO app_settings (setting_key, setting_value) KEY (setting_key) VALUES ('sponsor.balance.enabled', 'false');
SELECT setting_key, setting_value FROM app_settings WHERE setting_key = 'sponsor.balance.enabled';
