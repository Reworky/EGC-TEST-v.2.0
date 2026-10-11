-- Какая модель квестов выгоднее: «1 квест за большую сумму + кулдаун» или «много квестов за ту же сумму». Только чтение.
-- Нужны ДЕНЬГИ (доходы проекта, выплаты, реклама, стоки) и ПОВЕДЕНИЕ (вернулся ли игрок на следующий день после 1 квеста против 2+).
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-quest-model.sql
-- Окна: деньги - 30 дней, поведение - 30 дней без последних 3 (чтобы был срок «вернуться»). Время сервера.

-- ═══ A. ДЕНЬГИ ═══

-- A1) Доходы проекта по месяцам и видам (раздел «Доходы клуба»): рубли.
SELECT FORMATDATETIME(entry_date, 'yyyy-MM') AS ym, kind, COUNT(*) AS entries, SUM(amount_rub) AS rub
FROM finance_entries
WHERE entry_date >= DATEADD('DAY', -90, CURRENT_DATE)
GROUP BY FORMATDATETIME(entry_date, 'yyyy-MM'), kind
ORDER BY ym, kind;

-- A2) Пополнения фонда выплат по месяцам: рубли.
SELECT FORMATDATETIME(created_at, 'yyyy-MM') AS ym, COUNT(*) AS entries, SUM(amount_rub) AS pool_rub
FROM payout_pool_entries
WHERE created_at >= DATEADD('DAY', -90, CURRENT_TIMESTAMP)
GROUP BY FORMATDATETIME(created_at, 'yyyy-MM')
ORDER BY ym;

-- A3) Выплачено игрокам за 30 дней: заявок, игроков, EXC, рублей (вывод, статус APPROVED).
SELECT COUNT(*) AS payouts,
       COUNT(DISTINCT rr.user_id) AS players,
       SUM(COALESCE(rr.paid_price_coins, ri.price_coins)) AS exc,
       SUM(COALESCE(rr.fixed_rub_value, 0)) AS rub
FROM reward_requests rr
JOIN reward_items ri ON ri.id = rr.reward_id
WHERE ri.category = 'Вывод' AND rr.status = 'APPROVED' AND rr.paid_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP);

-- A4) Выдано и потрачено EXC по типам операций за 30 дней (issued - выдано игрокам, removed - списано).
SELECT type, COUNT(*) AS ops,
       SUM(CASE WHEN amount > 0 THEN amount ELSE 0 END) AS issued_exc,
       SUM(CASE WHEN amount < 0 THEN -amount ELSE 0 END) AS removed_exc
FROM exc_transactions
WHERE created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)
GROUP BY type
ORDER BY issued_exc DESC;

-- A5) Активные игроки за 30 дней (любое начисление) и за последние 7 дней - для пересчёта на одного игрока.
SELECT COUNT(DISTINCT CASE WHEN created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP) THEN user_id END) AS active_30d,
       COUNT(DISTINCT CASE WHEN created_at >= DATEADD('DAY', -7, CURRENT_TIMESTAMP) THEN user_id END) AS active_7d
FROM exc_transactions
WHERE amount > 0 AND created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP);

-- A6) Сколько квестов и EXC приходится на спонсорские и обычные квесты за 30 дней (засчитанные).
--     Важно: повторяемость имеет смысл только там, где за действие платит партнёр.
SELECT CASE WHEN q.sponsored THEN 'спонсорские' ELSE 'обычные' END AS quest_kind,
       COUNT(*) AS approved,
       COUNT(DISTINCT s.user_id) AS players,
       SUM(COALESCE(s.awarded_coins, 0)) AS exc
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
WHERE s.status = 'APPROVED' AND s.updated_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)
GROUP BY CASE WHEN q.sponsored THEN 'спонсорские' ELSE 'обычные' END;

-- ═══ B. РЕКЛАМА (откуда деньги с касаний) ═══

-- B1) Реклама за награду по местам за 30 дней: нажатий «смотреть» и выдано наград.
SELECT placement, SUM(requests) AS requests, SUM(rewards) AS rewards
FROM ad_placement_stats
WHERE day >= DATEADD('DAY', -30, CURRENT_DATE)
GROUP BY placement
ORDER BY requests DESC;

-- B2) Реклама AdsGram в боте за 30 дней: запросов, найдено, пусто, заполняемость.
SELECT SUM(requests) AS requests, SUM(filled) AS filled, SUM(empty) AS empty,
       ROUND(100.0 * SUM(filled) / NULLIF(SUM(requests), 0), 1) AS fill_pct
FROM bot_ad_stats
WHERE day >= DATEADD('DAY', -30, CURRENT_DATE);

-- B3) Выдано EXC за рекламу за 30 дней: наград, игроков, EXC (это «цена» касания рекламы).
SELECT COUNT(*) AS rewards, COUNT(DISTINCT user_id) AS players, SUM(amount) AS exc_paid
FROM exc_transactions
WHERE type = 'AD_REWARD' AND amount > 0 AND created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP);

-- ═══ C. КУЛДАУН КАК СТОК ═══

-- C1) Покупки «Снятие кулдауна квеста» за 30 дней: штук, игроков, EXC (стоимость 3 000 EXC за штуку).
SELECT COUNT(*) AS purchases, COUNT(DISTINCT user_id) AS players, SUM(-amount) AS exc_spent
FROM exc_transactions
WHERE type = 'SINK' AND description = 'Снятие кулдауна квеста' AND created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP);

-- ═══ D. ПОВЕДЕНИЕ: вернулся ли игрок после 1 квеста против 2+ в тот же день ═══
-- Группа = сколько квестов засчитано у игрока в день D. «Вернулся» = любое начисление (квест, бонус, сундук, реклама) в день D+1 / в D+1..D+3 / в D+1..D+7.
-- Оговорка: игроки с несколькими квестами в день могут быть просто активнее - смотрите разницу, но не принимайте её за причинно-следственную связь.

-- D1) Возврат по группам.
WITH q AS (
    SELECT s.user_id, CAST(s.updated_at AS DATE) AS d, COUNT(*) AS cnt
    FROM quest_submissions s
    WHERE s.status = 'APPROVED'
      AND s.updated_at >= DATEADD('DAY', -37, CURRENT_TIMESTAMP)
      AND CAST(s.updated_at AS DATE) < DATEADD('DAY', -7, CURRENT_DATE)
    GROUP BY s.user_id, CAST(s.updated_at AS DATE)
),
act AS (
    SELECT DISTINCT t.user_id, CAST(t.created_at AS DATE) AS d
    FROM exc_transactions t
    WHERE t.amount > 0 AND t.type IN ('QUEST', 'DAILY', 'CHEST', 'AD_REWARD')
      AND t.created_at >= DATEADD('DAY', -37, CURRENT_TIMESTAMP)
),
f AS (
    SELECT q.user_id, q.d, q.cnt,
           (SELECT COUNT(*) FROM act a WHERE a.user_id = q.user_id AND a.d = DATEADD('DAY', 1, q.d)) AS n1,
           (SELECT COUNT(*) FROM act a WHERE a.user_id = q.user_id AND a.d > q.d AND a.d <= DATEADD('DAY', 3, q.d)) AS n3,
           (SELECT COUNT(*) FROM act a WHERE a.user_id = q.user_id AND a.d > q.d AND a.d <= DATEADD('DAY', 7, q.d)) AS n7
    FROM q
)
SELECT CASE WHEN cnt >= 3 THEN '3+ квеста' ELSE CAST(cnt AS VARCHAR) || ' квест' END AS quests_that_day,
       COUNT(*) AS user_days,
       ROUND(100.0 * SUM(CASE WHEN n1 > 0 THEN 1 ELSE 0 END) / COUNT(*), 1) AS back_next_day_pct,
       ROUND(100.0 * SUM(CASE WHEN n3 > 0 THEN 1 ELSE 0 END) / COUNT(*), 1) AS back_in_3d_pct,
       ROUND(100.0 * SUM(CASE WHEN n7 > 0 THEN 1 ELSE 0 END) / COUNT(*), 1) AS back_in_7d_pct
FROM f
GROUP BY CASE WHEN cnt >= 3 THEN '3+ квеста' ELSE CAST(cnt AS VARCHAR) || ' квест' END
ORDER BY quests_that_day;

-- D2) Сколько EXC за квесты получает игрок в такой день (средняя «цена дня» по группам) - то есть во что обходится лишний квест.
WITH q AS (
    SELECT s.user_id, CAST(s.updated_at AS DATE) AS d, COUNT(*) AS cnt, SUM(COALESCE(s.awarded_coins, 0)) AS exc
    FROM quest_submissions s
    WHERE s.status = 'APPROVED' AND s.updated_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)
    GROUP BY s.user_id, CAST(s.updated_at AS DATE)
)
SELECT CASE WHEN cnt >= 3 THEN '3+ квеста' ELSE CAST(cnt AS VARCHAR) || ' квест' END AS quests_that_day,
       COUNT(*) AS user_days, ROUND(AVG(exc)) AS avg_exc_that_day, SUM(exc) AS total_exc
FROM q
GROUP BY CASE WHEN cnt >= 3 THEN '3+ квеста' ELSE CAST(cnt AS VARCHAR) || ' квест' END
ORDER BY quests_that_day;

-- ═══ E. КОНЦЕНТРАЦИЯ (риск фарма при «много квестов») ═══

-- E1) Доля EXC за квесты, которую получают самые активные: топ-10 игроков, топ-5% и остальные (30 дней).
WITH per AS (
    SELECT user_id, SUM(amount) AS exc FROM exc_transactions
    WHERE type = 'QUEST' AND amount > 0 AND created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)
    GROUP BY user_id
),
ranked AS (
    SELECT exc, ROW_NUMBER() OVER (ORDER BY exc DESC) AS rn, COUNT(*) OVER () AS n FROM per
)
SELECT SUM(exc) AS total_exc, MAX(n) AS players,
       SUM(CASE WHEN rn <= 10 THEN exc ELSE 0 END) AS top10_exc,
       ROUND(100.0 * SUM(CASE WHEN rn <= 10 THEN exc ELSE 0 END) / SUM(exc), 1) AS top10_pct,
       ROUND(100.0 * SUM(CASE WHEN rn <= GREATEST(1, CAST(n * 0.05 AS INT)) THEN exc ELSE 0 END) / SUM(exc), 1) AS top5pct_players_share_pct
FROM ranked;

-- Как читать:
--  A1-A3: сколько проект зарабатывает и сколько реально выплачивает - это бюджет, из которого оплачиваются любые квесты.
--  A4: выдача по типам; QUEST - то, на что влияет выбор модели. A6: сколько из квестовой выдачи приходится на спонсорские (там повтор оплачивает партнёр).
--  B: сколько стоит и приносит одно касание рекламы (нажатия/награды/заполняемость/EXC за рекламу).
--  C1: сколько EXC игроки тратят на снятие кулдауна - этот сток исчезает без кулдауна.
--  D1: если у групп «2» и «3+» возврат на следующий день НЕ выше (или ниже), чем у «1», лишние квесты в тот же день не помогают возврату.
--  E1: если топ-10 / топ-5% забирают непропорционально много, модель «много квестов» отдаст им основную часть выдачи.
