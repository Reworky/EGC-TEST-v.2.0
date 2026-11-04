-- Пилотный VPN в основном боте: аккаунты игроков (vpn_accounts). Только читает базу.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-vpn-accounts.sql
SELECT telegram_id, plan_type, status, created_at, expires_at, trial_used_at, reminder_stage
FROM vpn_accounts ORDER BY created_at;
SELECT plan_type, status, COUNT(*) AS n FROM vpn_accounts GROUP BY plan_type, status ORDER BY plan_type, status;
