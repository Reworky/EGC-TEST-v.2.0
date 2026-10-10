-- Включает обратно квесты «подпишись на канал» живых спонсорских кампаний, которые сидер ошибочно выключил при рестарте
-- (название канала совпало с названием игры без учёта регистра, баг исправлен 2026-10-10). Кампании, закрытые по лимиту/бюджету,
-- не трогает (у них sponsors.active = FALSE). Запускать ПОСЛЕ деплоя исправленной версии, иначе следующий рестарт снова выключит квест.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/reactivate-sponsor-channel-quests.sql
UPDATE quests SET active = TRUE
WHERE sponsored = TRUE AND channel_check_chat_id IS NOT NULL AND active = FALSE
  AND sponsor_id IN (SELECT id FROM sponsors WHERE active = TRUE);

SELECT q.id, q.title, q.active, s.name AS sponsor, s.active AS sponsor_active
FROM quests q JOIN sponsors s ON s.id = q.sponsor_id
WHERE q.channel_check_chat_id IS NOT NULL;
