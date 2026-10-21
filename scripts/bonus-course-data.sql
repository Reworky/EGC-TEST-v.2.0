-- Данные для рубрики «Курс дня» / «Красная кнопка» (раздел 11 ТЗ). Только чтение боевых данных.
-- Запуск на сервере: cd /root/gamebot && bash scripts/run-sql.sh scripts/bonus-course-data.sql
-- Время в базе - UTC (время сервера). МСК = UTC+3, часовой пояс владельца = UTC+7.
-- Единственное, что создаёт скрипт, - временная таблица w в рамках этой сессии (в конце удаляется).
-- Выводы: reward_items.category = 'Вывод'; без отменённых и отклонённых. Способ: STARS (purchase_group telegram_stars, курс 100 EXC = 1 ₽ без Health Ratio),
-- TON (в названии «(GRAM/TON)»), иначе RUB. Рубли заявки: у RUB/TON - fixed_rub_value (по курсу на момент заявки), у STARS - EXC / 100.

CREATE LOCAL TEMPORARY TABLE w AS
SELECT rr.id, rr.user_id, rr.created_at, rr.status,
       COALESCE(rr.paid_price_coins, ri.price_coins) AS exc,
       CASE WHEN ri.purchase_group = 'telegram_stars' THEN 'STARS'
            WHEN ri.title LIKE '%(GRAM/TON)%' THEN 'TON'
            ELSE 'RUB' END AS method,
       CASE WHEN ri.purchase_group = 'telegram_stars' THEN COALESCE(rr.paid_price_coins, ri.price_coins) / 100.0
            ELSE COALESCE(rr.fixed_rub_value, 0) END AS rub
FROM reward_requests rr
JOIN reward_items ri ON ri.id = rr.reward_id
WHERE ri.category = 'Вывод'
  AND rr.status NOT IN ('CANCELLED', 'REJECTED')
  AND rr.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP);

-- 0) Контроль: сколько заявок попало в выборку и за какой период
SELECT COUNT(*) AS requests, MIN(created_at) AS first_at, MAX(created_at) AS last_at FROM w;

-- 1) Заявки за 30 дней по способам: число, игроков, сумма EXC и ₽, средняя заявка в ₽
SELECT method, COUNT(*) AS requests, COUNT(DISTINCT user_id) AS users, SUM(exc) AS exc_total,
       ROUND(SUM(rub), 0) AS rub_total, ROUND(AVG(rub), 1) AS avg_rub
FROM w GROUP BY method ORDER BY method;

-- 2) По дням (UTC): заявок всего и по способам, сумма ₽
SELECT CAST(created_at AS DATE) AS day_utc, COUNT(*) AS requests,
       SUM(CASE WHEN method = 'RUB' THEN 1 ELSE 0 END) AS rub_cnt,
       SUM(CASE WHEN method = 'TON' THEN 1 ELSE 0 END) AS ton_cnt,
       SUM(CASE WHEN method = 'STARS' THEN 1 ELSE 0 END) AS stars_cnt,
       ROUND(SUM(rub), 0) AS rub_total
FROM w GROUP BY CAST(created_at AS DATE) ORDER BY day_utc;

-- 3) Распределение сумм заявки в ₽: рубли+TON отдельно от Stars (на них бонус будет считаться по-разному)
SELECT CASE WHEN method = 'STARS' THEN 'STARS' ELSE 'RUB+TON' END AS grp, COUNT(*) AS requests,
       ROUND(MIN(rub), 1) AS min_rub,
       ROUND(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY rub), 1) AS median_rub,
       ROUND(PERCENTILE_CONT(0.75) WITHIN GROUP (ORDER BY rub), 1) AS p75_rub,
       ROUND(PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY rub), 1) AS p90_rub,
       ROUND(MAX(rub), 1) AS max_rub
FROM w GROUP BY CASE WHEN method = 'STARS' THEN 'STARS' ELSE 'RUB+TON' END;

-- 4) Выплаты в ₽: всего за 30 дней, среднесуточно, за последние 7 дней среднесуточно (от этого считаются бюджеты: дневной ~12%, красная кнопка ~25% суточных выплат)
SELECT ROUND(SUM(rub), 0) AS rub_30d,
       ROUND(SUM(rub) / 30.0, 1) AS rub_per_day_30d,
       ROUND(SUM(CASE WHEN created_at >= DATEADD('DAY', -7, CURRENT_TIMESTAMP) THEN rub ELSE 0 END) / 7.0, 1) AS rub_per_day_7d,
       ROUND(SUM(rub) / 30.0 * 0.12, 1) AS daily_budget_12pct,
       ROUND(SUM(rub) / 30.0 * 0.25, 1) AS red_button_budget_25pct
FROM w;

-- 5) Повторные выводы: доля игроков с 2+ заявками за 30 дней и медианный интервал между заявками (часы)
SELECT COUNT(*) AS users, SUM(CASE WHEN c >= 2 THEN 1 ELSE 0 END) AS repeat_users,
       ROUND(SUM(CASE WHEN c >= 2 THEN 1 ELSE 0 END) * 100.0 / COUNT(*), 1) AS repeat_pct
FROM (SELECT user_id, COUNT(*) AS c FROM w GROUP BY user_id) t;

SELECT COUNT(*) AS intervals, ROUND(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY hrs), 1) AS median_hours_between
FROM (SELECT DATEDIFF('MINUTE', LAG(created_at) OVER (PARTITION BY user_id ORDER BY created_at), created_at) / 60.0 AS hrs FROM w) t
WHERE hrs IS NOT NULL;

-- 6) Активность по часам за 14 дней (UTC; МСК = +3, UTC+7 = +7): сколько разных игроков получали EXC в этот час.
-- Это замена «DAU по часам»: времени входов в базе нет, но время начислений есть.
SELECT EXTRACT(HOUR FROM created_at) AS hour_utc, COUNT(DISTINCT user_id) AS users, COUNT(*) AS credits
FROM exc_transactions
WHERE amount > 0 AND created_at >= DATEADD('DAY', -14, CURRENT_TIMESTAMP)
GROUP BY EXTRACT(HOUR FROM created_at) ORDER BY hour_utc;

-- 6б) Заявки на вывод по часам и дням недели (для выбора дней и окна красной кнопки)
SELECT EXTRACT(HOUR FROM created_at) AS hour_utc, COUNT(*) AS requests FROM w GROUP BY EXTRACT(HOUR FROM created_at) ORDER BY hour_utc;
SELECT ISO_DAY_OF_WEEK(created_at) AS weekday_1_mon, COUNT(*) AS requests FROM w GROUP BY ISO_DAY_OF_WEEK(created_at) ORDER BY weekday_1_mon;

-- 7) Аудитория бонуса: у кого вывод уже открыт и есть что выводить (3 квеста, 3 дня, 5 000+ EXC, не заблокирован и не заморожен)
-- Подписку на канал база не хранит: число подписчиков канала, которые есть в боте, нужно считать отдельно через getChatMember.
SELECT COUNT(*) AS can_withdraw_now,
       SUM(CASE WHEN coins >= 10000 THEN 1 ELSE 0 END) AS with_10k_plus,
       ROUND(AVG(coins), 0) AS avg_coins
FROM app_users
WHERE registration_completed = TRUE AND blocked = FALSE AND withdrawal_frozen = FALSE
  AND completed_quests >= 3 AND created_at <= DATEADD('DAY', -3, CURRENT_TIMESTAMP) AND coins >= 5000;

SELECT COUNT(DISTINCT user_id) AS active_14d FROM user_activity_days WHERE activity_day >= DATEADD('DAY', -14, CURRENT_DATE);

-- 8) Сценарий стоимости: бонус считается только с рублей и TON, верхняя граница = все активировали бонус.
-- Средний бонус 6,4% (из ТЗ) и 10,25% (мои стартовые веса), потолок на заявку 500 ₽ (из ТЗ) и 100 ₽ (моё предложение). Всё в ₽.
SELECT ROUND(SUM(LEAST(rub, 500)) * 0.064, 1) AS bonus_6_4_cap500_30d,
       ROUND(SUM(LEAST(rub, 100)) * 0.064, 1) AS bonus_6_4_cap100_30d,
       ROUND(SUM(LEAST(rub, 100)) * 0.1025, 1) AS bonus_10_25_cap100_30d,
       ROUND(SUM(LEAST(rub, 100)) * 0.1025 / 30.0, 1) AS bonus_10_25_cap100_per_day,
       ROUND(SUM(LEAST(rub, 100)) * 0.1025 * 0.5 / 30.0, 1) AS same_if_half_activate_per_day,
       ROUND(SUM(CASE WHEN rub > 100 THEN 1 ELSE 0 END) * 100.0 / COUNT(*), 1) AS pct_requests_over_cap100
FROM w WHERE method IN ('RUB', 'TON');

-- 9) Health Ratio сейчас и за 30 дней: от него зависит рублёвый курс (1 000 EXC = 10 ₽ × ratio)
SELECT ROUND(ratio, 3) AS ratio_now, payout_pool_rub, total_debt_exc, calculated_at
FROM health_ratio_snapshots ORDER BY calculated_at DESC LIMIT 1;
SELECT ROUND(MIN(ratio), 3) AS ratio_min_30d, ROUND(AVG(ratio), 3) AS ratio_avg_30d, ROUND(MAX(ratio), 3) AS ratio_max_30d
FROM health_ratio_snapshots WHERE calculated_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP);

DROP TABLE w;
