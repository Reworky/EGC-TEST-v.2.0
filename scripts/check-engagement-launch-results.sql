-- Первые итоги запусков 17-18.10: золотой час, код дня, цель клуба, A/B-рассылка «вывод открыт». Только чтение.
-- Запуск: cd /root/gamebot && git pull origin main && bash scripts/run-sql.sh scripts/check-engagement-launch-results.sql
-- Время сервера = UTC. (Алиас day в H2 зарезервирован - не использовать.) Золотой час по умолчанию 16:00-17:00 UTC (19:00-20:00 МСК).

-- 1) Окна золотого часа: заводились ли ежедневно (последние 10)
SELECT start_at, end_at, boost_percent, max_boosted_quests, boost_kind, active
FROM quest_reward_boost_events WHERE boost_kind = 'GOLDEN_HOUR' ORDER BY start_at DESC LIMIT 10;

-- 2) Засчитанные квесты по дням: в окне золотого часа (16-й час UTC) и за весь день, игроки в окне. Сравнить дни до запуска (до 17.10) и после
SELECT CAST(s.updated_at AS DATE) AS quest_date,
       SUM(CASE WHEN HOUR(s.updated_at) = 16 THEN 1 ELSE 0 END) AS quests_in_window,
       COUNT(DISTINCT CASE WHEN HOUR(s.updated_at) = 16 THEN s.user_id END) AS players_in_window,
       COUNT(*) AS quests_day, COUNT(DISTINCT s.user_id) AS players_day,
       ROUND(100.0 * SUM(CASE WHEN HOUR(s.updated_at) = 16 THEN 1 ELSE 0 END) / COUNT(*), 1) AS window_share_pct
FROM quest_submissions s
WHERE s.status = 'APPROVED' AND s.updated_at >= DATEADD('DAY', -16, CURRENT_TIMESTAMP)
GROUP BY CAST(s.updated_at AS DATE) ORDER BY quest_date;

-- 3) Код дня: сколько игроков ввели код, доля от активных в тот день (DAU по журналу)
SELECT CAST(c.published_at AS DATE) AS code_date, c.code, COUNT(cl.id) AS claims,
       (SELECT COUNT(*) FROM user_activity_days a WHERE a.activity_day = CAST(c.published_at AS DATE)) AS dau,
       ROUND(100.0 * COUNT(cl.id) / NULLIF((SELECT COUNT(*) FROM user_activity_days a WHERE a.activity_day = CAST(c.published_at AS DATE)), 0), 1) AS claims_pct_of_dau
FROM daily_codes c LEFT JOIN daily_code_claims cl ON cl.code_id = c.id
GROUP BY c.id, CAST(c.published_at AS DATE), c.code ORDER BY c.published_at DESC LIMIT 14;

-- 4) Цели клуба: тип, статус, набрано/нужно, сколько получили награду
SELECT g.id, g.goal_type, g.status, g.title, g.target_count, g.current_count, g.created_at, g.completed_at, g.expires_at,
       (SELECT COUNT(*) FROM club_goal_claims c WHERE c.goal_id = g.id) AS claims
FROM club_goals g ORDER BY g.created_at DESC LIMIT 10;

-- 5) A/B «вывод открыт» (A - получали сообщение, B - контроль): назначено, отправлено, подали заявку на вывод после назначения, заходили после назначения
SELECT n.grp, COUNT(*) AS assigned,
       SUM(CASE WHEN n.sent_at IS NOT NULL THEN 1 ELSE 0 END) AS sent,
       SUM(CASE WHEN EXISTS (SELECT 1 FROM reward_requests rr JOIN reward_items ri ON ri.id = rr.reward_id
                             WHERE rr.user_id = n.id AND ri.category = 'Вывод' AND rr.created_at > n.assigned_at) THEN 1 ELSE 0 END) AS withdrawal_after,
       SUM(CASE WHEN (CASE WHEN u.last_bot_activity_at IS NOT NULL AND (u.last_activity_date IS NULL OR CAST(u.last_bot_activity_at AS DATE) > u.last_activity_date)
                           THEN CAST(u.last_bot_activity_at AS DATE) ELSE COALESCE(u.last_activity_date, CAST(u.created_at AS DATE)) END) > CAST(n.assigned_at AS DATE)
                THEN 1 ELSE 0 END) AS visited_after,
       MIN(n.assigned_at) AS first_assigned
FROM withdrawal_nudges n JOIN app_users u ON u.id = n.id GROUP BY n.grp ORDER BY n.grp;
