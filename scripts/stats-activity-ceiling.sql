-- Что ограничивает активность: наши лимиты (кулдауны, слоты, сроки) или отсутствие интереса / внешние причины. Только чтение.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-activity-ceiling.sql
-- Как читать результаты - в конце файла.

-- 1) Что делает игрок после засчитанного квеста (60 дней): когда берёт следующий (любой) квест.
--    Горка на 23-27 ч = упирается в кулдаун 24 ч. Много «<1ч» и «1-23ч» = лимитов нет, играет сразу.
--    Много «72ч-14д» и «ушёл» = дело не в лимитах (интерес/другие причины).
SELECT bucket, COUNT(*) AS cnt, ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 1) AS pct
FROM (
    SELECT CASE
             WHEN nxt IS NULL AND done_at < DATEADD('DAY', -14, CURRENT_TIMESTAMP) THEN '9. ушёл (нет следующего 14+ дн.)'
             WHEN nxt IS NULL THEN '8. ещё не вернулся (<14 дн.)'
             WHEN DATEDIFF('MINUTE', done_at, nxt) < 60 THEN '1. сразу (<1 ч)'
             WHEN DATEDIFF('MINUTE', done_at, nxt) < 23 * 60 THEN '2. в тот же день (1-23 ч)'
             WHEN DATEDIFF('MINUTE', done_at, nxt) < 27 * 60 THEN '3. ровно после кулдауна (23-27 ч)'
             WHEN DATEDIFF('MINUTE', done_at, nxt) < 72 * 60 THEN '4. через 1-3 дня'
             ELSE '5. позже 3 дней'
           END AS bucket
    FROM (
        SELECT s.updated_at AS done_at,
               (SELECT MIN(n.created_at) FROM quest_submissions n WHERE n.user_id = s.user_id AND n.created_at > s.updated_at) AS nxt
        FROM quest_submissions s
        WHERE s.status = 'APPROVED' AND s.updated_at >= DATEADD('DAY', -60, CURRENT_TIMESTAMP)
    ) t
) b
GROUP BY bucket
ORDER BY bucket;

-- 2) Сколько реально занимает квест (взял -> засчитан), по играм: медиана и 90-й процентиль в минутах.
--    Если время выполнения мало, а сроки длинные, срок не ограничивает.
SELECT q.game_name, COUNT(*) AS approved,
       PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY DATEDIFF('MINUTE', s.created_at, s.updated_at)) AS median_min,
       PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY DATEDIFF('MINUTE', s.created_at, s.updated_at)) AS p90_min
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
WHERE s.status = 'APPROVED' AND s.updated_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)
GROUP BY q.game_name
HAVING COUNT(*) >= 5
ORDER BY approved DESC
LIMIT 12;

-- 3) Чем заканчиваются взятые квесты (30 дней): засчитан / на проверке / отклонён / отменён игроком / истёк срок / ещё идёт.
--    Много «отменён» и «истёк» = квесты сложные или неудобные, а не «не хотят играть».
SELECT CASE
         WHEN s.status = 'APPROVED' THEN '1. засчитан'
         WHEN s.status IN ('PENDING', 'NEEDS_INFO') THEN '2. на проверке / нужны данные'
         WHEN s.status = 'REJECTED' THEN '3. отклонён модератором'
         WHEN s.status = 'CANCELLED' THEN '4. отменён игроком'
         WHEN s.status = 'DRAFT' AND s.expires_at IS NOT NULL AND s.expires_at < CURRENT_TIMESTAMP THEN '5. истёк срок'
         ELSE '6. ещё идёт'
       END AS outcome,
       COUNT(*) AS cnt,
       ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 1) AS pct
FROM quest_submissions s
WHERE s.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)
GROUP BY CASE
         WHEN s.status = 'APPROVED' THEN '1. засчитан'
         WHEN s.status IN ('PENDING', 'NEEDS_INFO') THEN '2. на проверке / нужны данные'
         WHEN s.status = 'REJECTED' THEN '3. отклонён модератором'
         WHEN s.status = 'CANCELLED' THEN '4. отменён игроком'
         WHEN s.status = 'DRAFT' AND s.expires_at IS NOT NULL AND s.expires_at < CURRENT_TIMESTAMP THEN '5. истёк срок'
         ELSE '6. ещё идёт'
       END
ORDER BY outcome;

-- 4) Давление срока: в какой доле окна выполнения игроки сдают засчитанные квесты (30 дней).
--    Много сдач в последних 10% окна = срок реально подгоняет. Много в первых 10% = срок не ограничивает.
SELECT CASE
         WHEN ratio < 0.1 THEN '1. в первые 10% срока'
         WHEN ratio < 0.5 THEN '2. 10-50% срока'
         WHEN ratio < 0.9 THEN '3. 50-90% срока'
         ELSE '4. последние 10% срока'
       END AS when_done,
       COUNT(*) AS cnt, ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 1) AS pct
FROM (
    SELECT CAST(DATEDIFF('MINUTE', s.created_at, s.updated_at) AS DOUBLE)
           / NULLIF(DATEDIFF('MINUTE', s.created_at, s.expires_at), 0) AS ratio
    FROM quest_submissions s
    WHERE s.status = 'APPROVED' AND s.expires_at IS NOT NULL AND s.updated_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)
) r
WHERE ratio IS NOT NULL
GROUP BY CASE
         WHEN ratio < 0.1 THEN '1. в первые 10% срока'
         WHEN ratio < 0.5 THEN '2. 10-50% срока'
         WHEN ratio < 0.9 THEN '3. 50-90% срока'
         ELSE '4. последние 10% срока'
       END
ORDER BY when_done;

-- 5) Ритм игроков (30 дней): в скольких разных днях игрок брал квесты. Чем ближе к 30, тем ближе к потолку по частоте.
SELECT CASE
         WHEN days = 1 THEN '1 день'
         WHEN days = 2 THEN '2 дня'
         WHEN days <= 4 THEN '3-4 дня'
         WHEN days <= 9 THEN '5-9 дней'
         WHEN days <= 19 THEN '10-19 дней'
         ELSE '20+ дней'
       END AS active_days,
       COUNT(*) AS players,
       ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 1) AS pct
FROM (
    SELECT s.user_id, COUNT(DISTINCT CAST(s.created_at AS DATE)) AS days
    FROM quest_submissions s
    WHERE s.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)
    GROUP BY s.user_id
) d
GROUP BY CASE
         WHEN days = 1 THEN '1 день'
         WHEN days = 2 THEN '2 дня'
         WHEN days <= 4 THEN '3-4 дня'
         WHEN days <= 9 THEN '5-9 дней'
         WHEN days <= 19 THEN '10-19 дней'
         ELSE '20+ дней'
       END
ORDER BY MIN(days);

-- 6) Сколько игроков за 30 дней взяли ровно 1 квест за всё время (одноразовые) и сколько вернулись.
SELECT SUM(CASE WHEN cnt = 1 THEN 1 ELSE 0 END) AS took_once,
       SUM(CASE WHEN cnt >= 2 THEN 1 ELSE 0 END) AS took_2_plus,
       SUM(CASE WHEN cnt >= 5 THEN 1 ELSE 0 END) AS took_5_plus,
       COUNT(*) AS players
FROM (
    SELECT s.user_id, COUNT(*) AS cnt
    FROM quest_submissions s
    WHERE s.user_id IN (SELECT x.user_id FROM quest_submissions x WHERE x.created_at >= DATEADD('DAY', -30, CURRENT_TIMESTAMP))
    GROUP BY s.user_id
) c;

-- КАК ЧИТАТЬ:
--  (1) Больше ~30% возвращений на 23-27 ч = кулдаун 24 ч реально подгоняет активность (потолок наш).
--      Если большинство «ушёл»/«позже 3 дней» = люди сами не хотят возвращаться чаще: причина в интересе/мотивации/внешнем, не в лимитах.
--  (2)+(4) Если квесты проходят быстро и сдаются в первые 10-50% срока, срок не ограничивает.
--  (3) Много «отменён игроком» и «истёк срок» = сложность/неудобство квестов, не лимиты.
--  (5)+(6) Если большинство игроков берёт квесты 1-4 дня за месяц и многие один раз, потолок частоты далеко: проблема возврата, не лимитов.
