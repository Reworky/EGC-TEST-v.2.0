-- ВКЛЮЧИТЬ уровни вывода: партнёры и первые 10 000 EXC за всё время - курс 100%, остальные - 45% (x min(1, курс фонда / 0,9)), лимиты ранга остаются; рубли и крипта без партнёрского порога.
-- По умолчанию выключено. Включать вместе с новыми текстами экранов вывода. Выключить снова: заменить 'true' на 'false' или запустить scripts/wd-tiers-off.sql.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/wd-tiers-on.sql   (применяется сразу, без перезапуска)
MERGE INTO app_settings (setting_key, setting_value) KEY (setting_key) VALUES ('wd.tiers.enabled', 'true');
SELECT setting_key, setting_value FROM app_settings WHERE setting_key = 'wd.tiers.enabled';
