-- Отдельный бот EGC VPN и пилот: сводка для отчёта. Только читает базу.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-vpn2-status.sql
SELECT 'пилот: аккаунтов' AS metric, plan_type || '/' || status AS k, COUNT(*) AS n FROM vpn_accounts GROUP BY plan_type, status
UNION ALL SELECT 'пилот: платных', 'PAID', COUNT(*) FROM vpn_accounts WHERE plan_type = 'PAID'
UNION ALL SELECT 'бот: пользователей', 'все', COUNT(*) FROM vpn2_users
UNION ALL SELECT 'бот: пользователей', 'из игрового бота (from_egc)', COUNT(*) FROM vpn2_users WHERE source_code = 'egc'
UNION ALL SELECT 'бот: ключей', plan_type || '/' || status, COUNT(*) FROM vpn2_accounts GROUP BY plan_type, status
UNION ALL SELECT 'бот: оплат', 'всего', COUNT(*) FROM vpn2_payments
UNION ALL SELECT 'бот: отзывов и опросов', kind || '/' || status, COUNT(*) FROM vpn2_feedback GROUP BY kind, status;
SELECT 'пилот: пробные по дням выдачи' AS metric, CAST(created_at AS DATE) AS day_issued, COUNT(*) AS n FROM vpn_accounts GROUP BY CAST(created_at AS DATE) ORDER BY 2;
SELECT 'получили рассылку про VPN (запись)' AS metric, setting_key, setting_value FROM app_settings WHERE setting_key LIKE 'readybc.last.%';
