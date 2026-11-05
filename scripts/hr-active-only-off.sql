-- ОТКАТ курса фонда на старую формулу (долг = EXC ВСЕХ игроков, а не только активных за 30 дней).
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/hr-active-only-off.sql
-- Курс пересчитается в течение 4 часов (или сразу при пополнении фонда / перезапуске бота). Вернуть новую формулу: заменить 'false' на 'true' и запустить снова.
MERGE INTO app_settings (setting_key, setting_value) KEY (setting_key) VALUES ('hr.active.only', 'false');
