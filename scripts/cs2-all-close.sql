-- Закрывает выдачу кейсов, капсул и скинов CS2 для всех (техработы): карточки показывают «Скоро откроется», покупка отвечает «временно недоступны». Начатые заявки доводятся. Применяется сразу, без перезапуска.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/cs2-all-close.sql
MERGE INTO app_settings (setting_key, setting_value) KEY (setting_key) VALUES ('cs2.skins.paused', '1');
MERGE INTO app_settings (setting_key, setting_value) KEY (setting_key) VALUES ('cs2.cases.paused', '1');
