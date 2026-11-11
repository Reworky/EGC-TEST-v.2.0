-- Воронка к партнёрству: от регистрации до выплаты банка, отдельно для всех и для пришедших за последние 30 дней; плюс удержание целевой аудитории (18+, Россия). Только чтение.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-partner-funnel.sql
CREATE LOCAL TEMPORARY TABLE f AS
SELECT u.id, u.telegram_id, u.created_at,
  CASE WHEN u.registration_completed = TRUE THEN 1 ELSE 0 END AS registered,
  CASE WHEN u.age IS NOT NULL THEN 1 ELSE 0 END AS age_known,
  CASE WHEN u.age >= 18 THEN 1 ELSE 0 END AS adult,
  CASE WHEN LOWER(COALESCE(u.country, '')) IN ('россия', 'russia', 'рф', 'ru') THEN 1 ELSE 0 END AS ru,
  CASE WHEN u.completed_quests >= 1 THEN 1 ELSE 0 END AS q1,
  CASE WHEN u.completed_quests >= 3 THEN 1 ELSE 0 END AS q3,
  CASE WHEN EXISTS (SELECT 1 FROM user_activity_days d WHERE d.user_id = u.id AND d.activity_day >= DATEADD('DAY', -7, CURRENT_DATE)) THEN 1 ELSE 0 END AS act7,
  CASE WHEN EXISTS (SELECT 1 FROM partner_quest_events e WHERE e.telegram_id = u.telegram_id AND e.type = 'CARD_VIEWED') THEN 1 ELSE 0 END AS viewed,
  CASE WHEN EXISTS (SELECT 1 FROM partner_quest_events e WHERE e.telegram_id = u.telegram_id AND e.type = 'START_CLICKED') THEN 1 ELSE 0 END AS started_click,
  CASE WHEN EXISTS (SELECT 1 FROM quest_submissions s JOIN quests q ON q.id = s.quest_id WHERE s.user_id = u.id AND q.game_name IN ('Банк-партнёр', 'ОТП Банк')) THEN 1 ELSE 0 END AS took,
  CASE WHEN EXISTS (SELECT 1 FROM quest_submissions s JOIN quests q ON q.id = s.quest_id WHERE s.user_id = u.id AND q.game_name IN ('Банк-партнёр', 'ОТП Банк') AND s.card_activated_on IS NOT NULL) THEN 1 ELSE 0 END AS activated,
  CASE WHEN EXISTS (SELECT 1 FROM quest_submissions s JOIN quests q ON q.id = s.quest_id WHERE s.user_id = u.id AND q.game_name IN ('Банк-партнёр', 'ОТП Банк') AND s.purchase_reported_on IS NOT NULL) THEN 1 ELSE 0 END AS purchased,
  CASE WHEN EXISTS (SELECT 1 FROM quest_submissions s JOIN quests q ON q.id = s.quest_id WHERE s.user_id = u.id AND q.game_name IN ('Банк-партнёр', 'ОТП Банк') AND s.bonus_paid_at IS NOT NULL) THEN 1 ELSE 0 END AS paid
FROM app_users u WHERE COALESCE(u.blocked, FALSE) = FALSE;

-- 1) воронка: сколько на каждом шаге (all = вся база, new30 = аккаунты, созданные за последние 30 дней)
SELECT stage, SUM(a) AS all_accounts, SUM(n) AS new_30d FROM (
  SELECT '01 аккаунтов' AS stage, 1 AS a, CASE WHEN created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) THEN 1 ELSE 0 END AS n FROM f
  UNION ALL SELECT '02 прошли регистрацию', registered, registered * CASE WHEN created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) THEN 1 ELSE 0 END FROM f
  UNION ALL SELECT '03 указан возраст', age_known * registered, age_known * registered * CASE WHEN created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) THEN 1 ELSE 0 END FROM f
  UNION ALL SELECT '04 18+ и Россия', adult * ru * registered, adult * ru * registered * CASE WHEN created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) THEN 1 ELSE 0 END FROM f
  UNION ALL SELECT '05 из них >=1 квест', adult * ru * q1, adult * ru * q1 * CASE WHEN created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) THEN 1 ELSE 0 END FROM f
  UNION ALL SELECT '06 из них >=3 квестов', adult * ru * q3, adult * ru * q3 * CASE WHEN created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) THEN 1 ELSE 0 END FROM f
  UNION ALL SELECT '07 открыли карточку банка', viewed, viewed * CASE WHEN created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) THEN 1 ELSE 0 END FROM f
  UNION ALL SELECT '08 нажали «Начать»', started_click, started_click * CASE WHEN created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) THEN 1 ELSE 0 END FROM f
  UNION ALL SELECT '09 взяли квест (есть заявка)', took, took * CASE WHEN created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) THEN 1 ELSE 0 END FROM f
  UNION ALL SELECT '10 активировали карту', activated, activated * CASE WHEN created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) THEN 1 ELSE 0 END FROM f
  UNION ALL SELECT '11 сообщили о покупке', purchased, purchased * CASE WHEN created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) THEN 1 ELSE 0 END FROM f
  UNION ALL SELECT '12 банк заплатил (партнёр)', paid, paid * CASE WHEN created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) THEN 1 ELSE 0 END FROM f
) t GROUP BY stage ORDER BY stage;

-- 2) целевая аудитория (зарегистрированные 18+ Россия): сколько активны за 7 дней и как это связано с банковским квестом
SELECT CASE WHEN took = 1 THEN 'взяли банковский квест' ELSE 'не брали' END AS grp, COUNT(*) AS players, SUM(act7) AS active_7d,
       ROUND(100.0 * SUM(act7) / COUNT(*), 0) AS active_pct
FROM f WHERE registered = 1 AND adult = 1 AND ru = 1 GROUP BY CASE WHEN took = 1 THEN 'взяли банковский квест' ELSE 'не брали' END;

-- 3) куда уходят: на каком шаге банковской миссии застряли взявшие квест (по последней заявке)
SELECT s.status, s.mission_step, COUNT(*) AS submissions, SUM(CASE WHEN s.card_activated_on IS NOT NULL THEN 1 ELSE 0 END) AS activated,
       SUM(CASE WHEN s.bonus_paid_at IS NOT NULL THEN 1 ELSE 0 END) AS paid
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
WHERE q.game_name IN ('Банк-партнёр', 'ОТП Банк') GROUP BY s.status, s.mission_step ORDER BY s.status, s.mission_step;
