-- Почему не пришёл «Код дня»: включён ли автопост, час, дата последнего создания, висят ли неодобренные черновики. Только чтение.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-daily-code.sql
-- 1) настройки автопоста (cc.dcode.enabled = 1 включён; hour - час UTC; last - дата последнего созданного черновика)
SELECT s.setting_key, s.setting_value FROM app_settings s WHERE s.setting_key LIKE 'cc.dcode.%' ORDER BY s.setting_key;
-- 2) последние черновики кода дня: если сверху PENDING, он блокирует следующие (раньше навсегда)
SELECT d.id, d.status, CAST(d.created_at AS TIMESTAMP(0)) AS created, d.meta AS code FROM channel_post_drafts d WHERE d.type = 'DAILY_CODE' ORDER BY d.id DESC LIMIT 8;
-- 3) последние коды и сколько игроков ввело
SELECT c.id, c.code, CAST(c.published_at AS TIMESTAMP(0)) AS published, CAST(c.valid_until AS TIMESTAMP(0)) AS valid_until FROM daily_codes c ORDER BY c.id DESC LIMIT 5;
