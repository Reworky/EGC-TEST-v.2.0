-- Что за источник fdow1: название и расход из справочника, даты регистраций игроков, быстрая проверка на «нетипичность». Только чтение.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-traffic-source-fdow1.sql
-- 1) запись в справочнике источников
SELECT code, name, clicks, spend_rub, created_at FROM traffic_sources WHERE code IN ('fdow1', 'fhincc', 'snwow', 'etyinn');
-- 2) по дням: сколько игроков пришло с меткой fdow1
SELECT CAST(created_at AS DATE) AS day, COUNT(*) AS joined FROM app_users WHERE traffic_source_code = 'fdow1' GROUP BY CAST(created_at AS DATE) ORDER BY day;
-- 3) у них есть Telegram-ник и сколько реферальных/групповых признаков
SELECT COUNT(*) AS total, SUM(CASE WHEN referred_by_telegram_id IS NOT NULL THEN 1 ELSE 0 END) AS referred,
       SUM(CASE WHEN registration_completed THEN 1 ELSE 0 END) AS onboarded FROM app_users WHERE traffic_source_code = 'fdow1';
