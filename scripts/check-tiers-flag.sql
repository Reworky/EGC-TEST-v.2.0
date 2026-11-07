-- Включены ли уровни вывода: строка есть и значение true - включены (курс 100% партнёрам, дальше сниженный); строки нет или false - работает прежний курс фонда. Только читает базу.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-tiers-flag.sql
SELECT setting_key, setting_value FROM app_settings WHERE setting_key = 'wd.tiers.enabled';
