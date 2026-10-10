-- Включает онбординг v2 обратно (по умолчанию он и так включён, если строк нет): убирает флаги отката.
-- Запуск на сервере: cd /root/gamebot && bash scripts/run-sql.sh scripts/onboarding-v2-on.sql
DELETE FROM app_settings WHERE setting_key IN ('onboarding.v2.enabled', 'onboarding.first_quest_free.enabled');
SELECT setting_key, setting_value FROM app_settings WHERE setting_key LIKE 'onboarding.%';
