-- ЗАПИСЬ. Разовая правка заявки 10858 (игрок 1459489472, квест «Набери 22 убийства за матч»): база 124 матча осталась от прежнего Steam-аккаунта, у нового аккаунта матчей меньше, поэтому новые матчи не видны.
-- Сброс: следующий опрос бота (раз в 2 минуты) снимет базу заново по новому аккаунту; засчитываются матчи, сыгранные ПОСЛЕ этого. Запуск: cd /root/gamebot && git pull origin main && bash scripts/run-sql.sh scripts/fix-cs2-reset-baseline-10858.sql
UPDATE quest_submissions SET cs2baseline_value = NULL, cs2progress_count = 0 WHERE id = 10858 AND status = 'DRAFT';
SELECT s.id, s.status, s.cs2baseline_value AS baseline, s.cs2progress_count AS progress FROM quest_submissions s WHERE s.id = 10858;
