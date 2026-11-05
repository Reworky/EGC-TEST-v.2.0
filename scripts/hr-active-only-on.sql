-- Включить (или вернуть) расчёт курса фонда по активным за 30 дней: флаг hr.active.only = true.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/hr-active-only-on.sql
-- Курс пересчитается в течение 4 часов (или сразу при пополнении фонда / перезапуске бота). Отключить: scripts/hr-active-only-off.sql.
MERGE INTO app_settings (setting_key, setting_value) KEY (setting_key) VALUES ('hr.active.only', 'true');
