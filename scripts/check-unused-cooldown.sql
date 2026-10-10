-- Игроки с купленным, но так и не применённым «Снятием кулдауна» (баг заявки #279: кнопка «Взять» была серой, пока флаг не применён).
-- Только чтение. Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-unused-cooldown.sql
SELECT telegram_id, nickname, coins, cooldown_bypass_game, daily_cooldown_removals, daily_cooldown_date
FROM app_users
WHERE cooldown_bypass_game IS NOT NULL
ORDER BY daily_cooldown_date DESC;
