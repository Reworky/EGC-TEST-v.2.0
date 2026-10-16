-- Мультиаккаунты через общий игровой аккаунт: один тег Brawl Stars / Clash Royale / Clash of Clans / Dota 2 / CS2 привязан к нескольким аккаунтам клуба.
-- Только чтение. Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-shared-game-tags.sql
-- Как читать: строка = один игровой аккаунт, привязанный к 2+ аккаунтам EGC (accounts); ids - их Telegram ID; quests - сумма выполненных квестов этих аккаунтов.
-- Пустой результат под заголовком - таких совпадений нет. Если один и тот же тег стоит у нескольких аккаунтов, автоматические квесты (прирост от базы)
-- могут оплачиваться за одну и ту же игру несколько раз.

-- Brawl Stars
SELECT UPPER(u.brawl_stars_tag) AS game_tag, COUNT(*) AS accounts, SUM(u.completed_quests) AS quests, LISTAGG(CAST(u.telegram_id AS VARCHAR), ',') AS ids
FROM app_users u WHERE u.brawl_stars_tag IS NOT NULL AND u.brawl_stars_tag <> '' GROUP BY UPPER(u.brawl_stars_tag) HAVING COUNT(*) > 1 ORDER BY 2 DESC, 3 DESC;

-- Clash Royale
SELECT UPPER(u.clash_royale_tag) AS game_tag, COUNT(*) AS accounts, SUM(u.completed_quests) AS quests, LISTAGG(CAST(u.telegram_id AS VARCHAR), ',') AS ids
FROM app_users u WHERE u.clash_royale_tag IS NOT NULL AND u.clash_royale_tag <> '' GROUP BY UPPER(u.clash_royale_tag) HAVING COUNT(*) > 1 ORDER BY 2 DESC, 3 DESC;

-- Clash of Clans
SELECT UPPER(u.clash_of_clans_tag) AS game_tag, COUNT(*) AS accounts, SUM(u.completed_quests) AS quests, LISTAGG(CAST(u.telegram_id AS VARCHAR), ',') AS ids
FROM app_users u WHERE u.clash_of_clans_tag IS NOT NULL AND u.clash_of_clans_tag <> '' GROUP BY UPPER(u.clash_of_clans_tag) HAVING COUNT(*) > 1 ORDER BY 2 DESC, 3 DESC;

-- Dota 2
SELECT CAST(u.dota_account_id AS VARCHAR) AS game_tag, COUNT(*) AS accounts, SUM(u.completed_quests) AS quests, LISTAGG(CAST(u.telegram_id AS VARCHAR), ',') AS ids
FROM app_users u WHERE u.dota_account_id IS NOT NULL GROUP BY u.dota_account_id HAVING COUNT(*) > 1 ORDER BY 2 DESC, 3 DESC;

-- CS2 (в базе колонка cs2steam_id64: Hibernate не ставит подчёркивание после цифры)
SELECT CAST(u.cs2steam_id64 AS VARCHAR) AS game_tag, COUNT(*) AS accounts, SUM(u.completed_quests) AS quests, LISTAGG(CAST(u.telegram_id AS VARCHAR), ',') AS ids
FROM app_users u WHERE u.cs2steam_id64 IS NOT NULL GROUP BY u.cs2steam_id64 HAVING COUNT(*) > 1 ORDER BY 2 DESC, 3 DESC;
