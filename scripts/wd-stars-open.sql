-- ОТКРЫТЬ вывод звёздами (по умолчанию закрыт с 05.11.2026: «временно недоступно»). Закрыть снова: заменить 'true' на 'false'.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/wd-stars-open.sql   (применяется сразу, без перезапуска)
MERGE INTO app_settings (setting_key, setting_value) KEY (setting_key) VALUES ('wd.stars.enabled', 'true');
