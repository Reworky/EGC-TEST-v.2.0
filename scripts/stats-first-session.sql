-- Первая сессия новичка по дням регистрации (для сравнения «до/после» онбординга v2). Только чтение.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-first-session.sql

-- 1) По дням за 30 дней: сколько новичков, сколько взяли квест в первые 24 часа, сколько получили награду в первые 24 часа,
--    сколько подписалось на канал (registration_completed), сколько удерживается бонус за первый квест до подписки.
SELECT CAST(u.created_at AS DATE) AS reg_day,
       COUNT(*) AS new_players,
       SUM(CASE WHEN EXISTS (SELECT 1 FROM quest_submissions s WHERE s.user_id = u.id AND s.status <> 'DRAFT'
                              AND s.created_at < DATEADD('HOUR', 24, u.created_at)) THEN 1 ELSE 0 END) AS took_quest_24h,
       SUM(CASE WHEN EXISTS (SELECT 1 FROM quest_submissions s WHERE s.user_id = u.id AND s.status = 'APPROVED'
                              AND s.updated_at < DATEADD('HOUR', 24, u.created_at)) THEN 1 ELSE 0 END) AS got_reward_24h,
       SUM(CASE WHEN u.registration_completed = TRUE THEN 1 ELSE 0 END) AS subscribed,
       SUM(CASE WHEN u.referral_first_quest_bonus_pending = TRUE THEN 1 ELSE 0 END) AS first_bonus_held
FROM app_users u
WHERE u.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)
GROUP BY CAST(u.created_at AS DATE)
ORDER BY reg_day;

-- 2) Подписка после первой награды (только для прошедших первый квест без подписки): сколько из них всё же подписалось.
SELECT COUNT(*) AS first_quest_done_unsubscribed_or_later,
       SUM(CASE WHEN u.registration_completed = TRUE THEN 1 ELSE 0 END) AS later_subscribed
FROM app_users u
WHERE u.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)
  AND EXISTS (SELECT 1 FROM quest_submissions s WHERE s.user_id = u.id AND s.status = 'APPROVED');
