-- Какие категории у АКТИВНЫХ квестов CS2 (от этого зависит общий кулдаун игры). Только чтение.
-- Запуск на сервере: cd /root/gamebot && git pull origin main && bash scripts/run-sql.sh scripts/check-cs2-active-categories.sql
-- category = NULL у всех активных квестов -> один общий кулдаун на всю игру (24 ч после любого засчитанного квеста); есть Лёгкие/Средние/Сложные -> у каждой категории свой кулдаун.

SELECT COALESCE(q.category, '(нет категории)') AS category, COUNT(*) AS active_quests
FROM quests q WHERE q.game_name = 'CS2' AND q.active = TRUE
GROUP BY q.category ORDER BY COUNT(*) DESC;
