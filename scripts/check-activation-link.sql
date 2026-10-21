-- Ссылка-приглашение, которую бот выдаёт игрокам на кнопке «📢 Подписаться на канал» (регистрация/взятие квеста). Только чтение.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-activation-link.sql
SELECT setting_key, setting_value FROM app_settings WHERE setting_key = 'bot.activation.invite_link';
