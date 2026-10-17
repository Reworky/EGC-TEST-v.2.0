-- В1: участники отрядов, у которых реквизиты вывода совпадают с реквизитами другого аккаунта (любого игрока). Сами реквизиты не выводятся.
-- Ключ: для TON/USDT - адрес (нижний регистр), для остальных - последние 10 цифр. Заявки любого статуса.
WITH raw AS (SELECT rr.user_id, rr.payout_details AS d FROM reward_requests rr JOIN reward_items ri ON ri.id = rr.reward_id
             WHERE ri.category = 'Вывод' AND rr.payout_details IS NOT NULL AND rr.payout_details <> ''),
after_bank AS (SELECT user_id, d,
                      CASE WHEN POSITION('Реквизит:' IN d) > 0 THEN TRIM(SUBSTRING(d, POSITION('Реквизит:' IN d) + 9)) ELSE d END AS req FROM raw),
keyed AS (SELECT DISTINCT user_id,
                 CASE WHEN UPPER(req) LIKE 'TON:%' OR UPPER(req) LIKE 'USDT%'
                      THEN 'w:' || LOWER(TRIM(CASE WHEN POSITION(':rubles=' IN SUBSTRING(req, POSITION(':' IN req) + 1)) > 0
                                                   THEN SUBSTRING(SUBSTRING(req, POSITION(':' IN req) + 1), 1, POSITION(':rubles=' IN SUBSTRING(req, POSITION(':' IN req) + 1)) - 1)
                                                   ELSE SUBSTRING(req, POSITION(':' IN req) + 1) END))
                      WHEN LENGTH(REGEXP_REPLACE(req, '[^0-9]', '')) >= 10
                      THEN 'd:' || RIGHT(REGEXP_REPLACE(req, '[^0-9]', ''), 10)
                      ELSE NULL END AS k
          FROM after_bank)
SELECT m.id AS user_id, m.telegram_id, m.squad_id,
       COUNT(DISTINCT o.user_id) AS other_accounts_with_same_details,
       SUM(CASE WHEN ou.squad_id = m.squad_id THEN 1 ELSE 0 END) AS of_them_in_same_squad
FROM keyed a
JOIN app_users m ON m.id = a.user_id AND m.squad_id IS NOT NULL AND m.registration_completed = TRUE
JOIN keyed o ON o.k = a.k AND o.user_id <> a.user_id
JOIN app_users ou ON ou.id = o.user_id
WHERE a.k IS NOT NULL
GROUP BY m.id, m.telegram_id, m.squad_id
ORDER BY other_accounts_with_same_details DESC, m.squad_id
