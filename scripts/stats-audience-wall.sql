-- Аудитория и «стена» вывода: кто активен, из какой страны, какого возраста, сколько уже вывел и сколько лежит на балансе. Только читает базу.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-audience-wall.sql
-- Активный = регистрация завершена, не забанен, был в боте или мини-аппе за последние 30 дней.
CREATE LOCAL TEMPORARY TABLE wd AS
SELECT r.user_id, SUM(COALESCE(r.paid_price_coins, i.price_coins)) AS withdrawn
FROM reward_requests r JOIN reward_items i ON i.id = r.reward_id
WHERE i.category = 'Вывод' AND r.status = 'APPROVED' GROUP BY r.user_id;

CREATE LOCAL TEMPORARY TABLE act AS
SELECT u.id, u.coins, u.completed_quests AS quests,
  COALESCE(NULLIF(u.country, ''), 'не указана') AS country,
  CASE WHEN u.age IS NULL THEN 'возраст неизвестен' WHEN u.age < 18 THEN 'младше 18' ELSE '18+' END AS age_grp,
  COALESCE(w.withdrawn, 0) AS withdrawn,
  CASE WHEN EXISTS (SELECT 1 FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
                    WHERE s.user_id = u.id AND q.game_name = 'ОТП Банк' AND s.status = 'APPROVED') THEN 1 ELSE 0 END AS has_partner
FROM app_users u LEFT JOIN wd w ON w.user_id = u.id
WHERE u.registration_completed = TRUE AND COALESCE(u.blocked, FALSE) = FALSE
  AND GREATEST(COALESCE(u.last_activity_date, DATE '2000-01-01'),
               CAST(COALESCE(u.last_bot_activity_at, TIMESTAMP '2000-01-01 00:00:00') AS DATE),
               CAST(COALESCE(u.last_mini_app_open_at, TIMESTAMP '2000-01-01 00:00:00') AS DATE)) >= DATEADD('DAY', -30, CURRENT_DATE);

SELECT '1. активных за 30 дней' AS metric, COUNT(*) AS n FROM act
UNION ALL SELECT '2. с одобренным квестом ОТП', COUNT(*) FROM act WHERE has_partner = 1
UNION ALL SELECT '3. уже вывели 10 000+ EXC за всё время (у стены)', COUNT(*) FROM act WHERE withdrawn >= 10000
UNION ALL SELECT '4. у стены и баланс 10 000+ (ждут выхода)', COUNT(*) FROM act WHERE withdrawn >= 10000 AND coins >= 10000
UNION ALL SELECT '5. ещё не выводили ничего, баланс 10 000+', COUNT(*) FROM act WHERE withdrawn = 0 AND coins >= 10000
ORDER BY 1;

SELECT 'страна' AS grp, country AS value, COUNT(*) AS n FROM act GROUP BY country ORDER BY n DESC LIMIT 12;
SELECT 'возраст' AS grp, age_grp AS value, COUNT(*) AS n FROM act GROUP BY age_grp ORDER BY n DESC;
SELECT 'возраст x страна (РФ)' AS grp, age_grp AS value, COUNT(*) AS n FROM act WHERE country = 'Россия' GROUP BY age_grp ORDER BY n DESC;

SELECT 'баланс' AS grp, bucket AS value, COUNT(*) AS n FROM (
  SELECT CASE WHEN coins < 5000 THEN 'a. до 5 000' WHEN coins < 10000 THEN 'b. 5-10 тыс' WHEN coins < 25000 THEN 'c. 10-25 тыс'
              WHEN coins < 50000 THEN 'd. 25-50 тыс' ELSE 'e. 50 тыс+' END AS bucket FROM act) x GROUP BY bucket ORDER BY bucket;

SELECT 'у стены: баланс (вывели 10 000+)' AS grp, bucket AS value, COUNT(*) AS n FROM (
  SELECT CASE WHEN coins < 5000 THEN 'a. до 5 000' WHEN coins < 10000 THEN 'b. 5-10 тыс' WHEN coins < 25000 THEN 'c. 10-25 тыс'
              WHEN coins < 50000 THEN 'd. 25-50 тыс' ELSE 'e. 50 тыс+' END AS bucket FROM act WHERE withdrawn >= 10000) x GROUP BY bucket ORDER BY bucket;
