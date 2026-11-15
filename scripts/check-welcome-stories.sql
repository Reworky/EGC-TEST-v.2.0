-- Welcome stories (слайды после /start): сколько новичков увидели, дошли до 2-го и 3-го слайда, нажали «Начать играть» или «Пропустить». Только чтение.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-welcome-stories.sql
SELECT setting_key, setting_value FROM app_settings WHERE setting_key LIKE 'welcome.stat.%' ORDER BY setting_key;
-- shown - показан 1-й слайд; s2 / s3 - дошли до 2-го / 3-го; done - «Начать играть»; skip - «Пропустить».
-- Выключить без деплоя: UPDATE app_settings SET setting_value = 'false' WHERE setting_key = 'onboarding.welcome_stories.enabled'; (если строки нет - INSERT с тем же ключом и 'false')
