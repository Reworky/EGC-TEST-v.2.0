-- Мультиаккаунты через общий игровой аккаунт: один тег Brawl Stars / Clash Royale / Clash of Clans / Dota 2 / CS2 привязан к нескольким аккаунтам клуба.
-- Только чтение. Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-shared-game-tags.sql
-- Как читать: строка = один игровой аккаунт, привязанный к 2+ аккаунтам EGC (accounts); ids - их Telegram ID; quests - сумма выполненных квестов этих аккаунтов.
-- Если один и тот же тег стоит у нескольких аккаунтов, автоматические квесты (прирост от базы) могут оплачиваться за одну и ту же игру несколько раз.

SELECT 'Brawl Stars' AS game, UPPER(u.brawl_stars_tag) AS game_tag, COUNT(*) AS accounts, SUM(u.completed_quests) AS quests, LISTAGG(CAST(u.telegram_id AS VARCHAR), ',') AS ids
FROM app_users u WHERE u.brawl_stars_tag IS NOT NULL AND u.brawl_stars_tag <> '' GROUP BY UPPER(u.brawl_stars_tag) HAVING COUNT(*) > 1
UNION ALL
SELECT 'Clash Royale', UPPER(u.clash_royale_tag), COUNT(*), SUM(u.completed_quests), LISTAGG(CAST(u.telegram_id AS VARCHAR), ',')
FROM app_users u WHERE u.clash_royale_tag IS NOT NULL AND u.clash_royale_tag <> '' GROUP BY UPPER(u.clash_royale_tag) HAVING COUNT(*) > 1
UNION ALL
SELECT 'Clash of Clans', UPPER(u.clash_of_clans_tag), COUNT(*), SUM(u.completed_quests), LISTAGG(CAST(u.telegram_id AS VARCHAR), ',')
FROM app_users u WHERE u.clash_of_clans_tag IS NOT NULL AND u.clash_of_clans_tag <> '' GROUP BY UPPER(u.clash_of_clans_tag) HAVING COUNT(*) > 1
UNION ALL
SELECT 'Dota 2', CAST(u.dota_account_id AS VARCHAR), COUNT(*), SUM(u.completed_quests), LISTAGG(CAST(u.telegram_id AS VARCHAR), ',')
FROM app_users u WHERE u.dota_account_id IS NOT NULL GROUP BY u.dota_account_id HAVING COUNT(*) > 1
UNION ALL
SELECT 'CS2', CAST(u.cs2_steam_id64 AS VARCHAR), COUNT(*), SUM(u.completed_quests), LISTAGG(CAST(u.telegram_id AS VARCHAR), ',')
FROM app_users u WHERE u.cs2_steam_id64 IS NOT NULL GROUP BY u.cs2_steam_id64 HAVING COUNT(*) > 1
ORDER BY 3 DESC, 4 DESC;
