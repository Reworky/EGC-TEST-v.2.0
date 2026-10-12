-- Воронка спонсорских квестов «подпишись на канал» по квестам: отправлено → открыли → взяли → выполнили → остались. Только чтение.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-sponsor-funnel.sql
-- «Показано в Играть» - сколько игроков увидели квест как рекомендацию; «открыли» - открыли карточку квеста (раз на игрока).
SELECT q.id AS quest_id, q.title,
       (SELECT COUNT(DISTINCT e.user_id) FROM sponsor_quest_events e WHERE e.quest_id = q.id AND e.kind = 'PUSH') AS pushed,
       (SELECT COUNT(DISTINCT e.user_id) FROM sponsor_quest_events e WHERE e.quest_id = q.id AND e.kind = 'REMINDER') AS reminded,
       (SELECT COUNT(DISTINCT e.user_id) FROM sponsor_quest_events e WHERE e.quest_id = q.id AND e.kind = 'SHOWN_PLAY') AS shown_in_play,
       (SELECT COUNT(DISTINCT e.user_id) FROM sponsor_quest_events e WHERE e.quest_id = q.id AND e.kind = 'VIEWED') AS viewed,
       (SELECT COUNT(*) FROM quest_submissions s WHERE s.quest_id = q.id) AS taken,
       (SELECT COUNT(*) FROM quest_submissions s WHERE s.quest_id = q.id AND s.status = 'APPROVED') AS completed,
       (SELECT COUNT(*) FROM quest_submissions s WHERE s.quest_id = q.id AND s.status = 'APPROVED' AND s.channel_left_at IS NULL) AS still_in_channel
FROM quests q
WHERE q.sponsored = TRUE AND q.channel_check_chat_id IS NOT NULL
ORDER BY q.id DESC;

-- Когда игроки открывали карточку после пуша: по часам (UTC) - подсказка, в какое время слать.
SELECT HOUR(e.created_at) AS hour_utc, COUNT(*) AS views
FROM sponsor_quest_events e
WHERE e.kind = 'VIEWED'
GROUP BY HOUR(e.created_at)
ORDER BY hour_utc;
