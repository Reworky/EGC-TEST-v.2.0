-- «Цель недели не засчитывает действия / билеты» - диагностика по одному игроку. Только чтение.
-- Замените 5069807631 на Telegram ID игрока (во всех запросах). Запуск: cd /root/gamebot && git pull origin main && bash scripts/run-sql.sh scripts/check-player-weekly-goal.sql
-- Что засчитывает день цели недели (WeeklyGoalService.activeDays): засчитанный квест (APPROVED), ежедневный бонус (DAILY), код дня.
-- Вращение колеса фортуны и трата билетов день НЕ засчитывают. Дни считаются по UTC (время сервера).
-- Награда за цель: +200 EXC и +1 билет, тип транзакции WEEKLY_GOAL; награду выдаёт только кнопка «Забрать награду».

-- 1) Игрок: баланс билетов
SELECT u.id AS user_id, u.telegram_id, u.nickname, u.tickets, u.coins, CURRENT_DATE AS server_today
FROM app_users u WHERE u.telegram_id = 5069807631;

-- 2) Забранные награды за цель недели (по неделям)
SELECT c.week_start, c.claimed_at
FROM weekly_goal_claims c JOIN app_users u ON u.id = c.user_id
WHERE u.telegram_id = 5069807631 ORDER BY c.week_start DESC;

-- 3) Активные дни за последние 14 суток по источникам (засчитанные квесты)
SELECT CAST(s.updated_at AS DATE) AS day, 'квест APPROVED' AS source, COUNT(*) AS n
FROM quest_submissions s JOIN app_users u ON u.id = s.user_id
WHERE u.telegram_id = 5069807631 AND s.status = 'APPROVED' AND s.updated_at >= DATEADD('DAY', -14, CURRENT_TIMESTAMP)
GROUP BY CAST(s.updated_at AS DATE) ORDER BY day;

-- 4) Ежедневный бонус и выдача за цель недели в журнале EXC за 14 суток
SELECT t.created_at, t.type, t.amount, t.description
FROM exc_transactions t JOIN app_users u ON u.id = t.user_id
WHERE u.telegram_id = 5069807631 AND t.type IN ('DAILY', 'WEEKLY_GOAL') AND t.created_at >= DATEADD('DAY', -14, CURRENT_TIMESTAMP)
ORDER BY t.created_at;

-- 5) Коды дня за 14 суток
SELECT d.claim_date
FROM daily_code_claims d JOIN app_users u ON u.id = d.user_id
WHERE u.telegram_id = 5069807631 AND d.claim_date >= DATEADD('DAY', -14, CURRENT_DATE)
ORDER BY d.claim_date;
