-- ОТКАТ онбординга v2 без деплоя: возвращает старый вход (приветствие + инструкция) и обязательную подписку до первого квеста.
-- Меняет данные (две строки в app_settings). Запуск на сервере: cd /root/gamebot && bash scripts/run-sql.sh scripts/onboarding-v2-off.sql
-- Включить обратно: bash scripts/run-sql.sh scripts/onboarding-v2-on.sql
MERGE INTO app_settings (setting_key, setting_value) KEY (setting_key) VALUES ('onboarding.v2.enabled', 'false');
MERGE INTO app_settings (setting_key, setting_value) KEY (setting_key) VALUES ('onboarding.first_quest_free.enabled', 'false');
SELECT setting_key, setting_value FROM app_settings WHERE setting_key LIKE 'onboarding.%';
