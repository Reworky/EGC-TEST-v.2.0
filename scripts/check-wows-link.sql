-- Проверка ссылки в квесте World of Warships после деплоя (только чтение).
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-wows-link.sql
-- Ожидаем: в instruction ссылка https://igro-afisha.pages.dev/?tg={TG_ID}, ключ wows.card.v6.done есть.
SELECT id, title, active, instruction FROM quest WHERE game_name = 'World of Warships';
SELECT setting_key, setting_value FROM app_settings WHERE setting_key LIKE 'wows.card.%';
