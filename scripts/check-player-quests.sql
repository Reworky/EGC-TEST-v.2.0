-- Диагностика «квест не засчитывается» по одному игроку: что бот знает про его заявки. Только чтение.
-- Замените 1041652225 на Telegram ID игрока. Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-player-quests.sql
-- Как читать:
--  brawl_battle_cursor пуст И прогресс 0 -> бот ни разу не нашёл бои игрока (пустой battlelog / тег не сошёлся в бою): проблема в теге/данных.
--  cursor заполнен, прогресс 0 -> бои находятся, но не подходят под условия квеста (нужная победа/боец/режим).
--  status CANCELLED у ранних заявок -> игрок сам отменил и взял следующий квест: прогресс считается только с момента взятия.

SELECT u.id AS user_id, u.nickname, u.brawl_stars_tag, u.brawl_tag_confirmed_at, u.created_at AS registered_at
FROM app_users u WHERE u.telegram_id = 1041652225;

SELECT s.id, s.created_at AS taken_at, s.updated_at, s.status, s.brawl_progress_count AS progress, q.brawl_target_count AS target,
       s.brawl_battle_cursor, q.brawl_brawler_names, q.brawl_mode_keys, q.brawl_require_victory, q.title
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
WHERE s.user_id = (SELECT id FROM app_users WHERE telegram_id = 1041652225)
ORDER BY s.created_at;
