-- Открыт ли вывод звёздами: строка есть и значение true - открыт; строки нет или false - закрыт ("временно недоступно"). Только читает базу.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-stars-flag.sql
SELECT setting_key, setting_value FROM app_settings WHERE setting_key = 'wd.stars.enabled';
