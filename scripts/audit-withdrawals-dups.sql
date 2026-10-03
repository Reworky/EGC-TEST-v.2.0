-- Кто стоит за одинаковыми реквизитами вывода (только чтение). Запуск: bash scripts/run-sql.sh scripts/audit-withdrawals-dups.sql
-- Игроки и их заявки на эти два номера: признаки одного человека - регистрация подряд, одно приглашение, один отряд, похожие ники.
SELECT rr.id AS req, rr.status, rr.created_at AS req_at, rr.paid_price_coins AS exc,
       u.id AS user_id, u.telegram_id, u.telegram_username, u.nickname, u.created_at AS registered_at,
       u.xp, u.coins, u.completed_quests AS quests, u.referred_by_telegram_id AS referred_by, u.squad_id, u.blocked,
       REGEXP_REPLACE(rr.payout_details, ':rubles=.*$', '') AS destination
FROM reward_requests rr
JOIN reward_items ri ON ri.id = rr.reward_id
JOIN app_users u ON u.id = rr.user_id
WHERE ri.category = 'Вывод'
  AND (rr.payout_details LIKE '%+7 914 883 64 06%' OR rr.payout_details LIKE '%+7 992 234 30 32%')
ORDER BY destination, rr.created_at;

-- Те же четверо: приглашали ли они друг друга (referred_by одного равен telegram_id другого) и общий отряд
SELECT a.telegram_id AS tg_a, a.nickname AS nick_a, b.telegram_id AS tg_b, b.nickname AS nick_b,
       CASE WHEN a.referred_by_telegram_id = b.telegram_id THEN 'A приглашён B'
            WHEN b.referred_by_telegram_id = a.telegram_id THEN 'B приглашён A'
            WHEN a.referred_by_telegram_id = b.referred_by_telegram_id AND a.referred_by_telegram_id IS NOT NULL THEN 'один пригласивший'
            WHEN a.squad_id = b.squad_id AND a.squad_id IS NOT NULL THEN 'один отряд'
            ELSE 'связи не видно' END AS link
FROM app_users a
JOIN app_users b ON a.id < b.id
WHERE a.id IN (SELECT rr.user_id FROM reward_requests rr WHERE rr.payout_details LIKE '%+7 914 883 64 06%' OR rr.payout_details LIKE '%+7 992 234 30 32%')
  AND b.id IN (SELECT rr.user_id FROM reward_requests rr WHERE rr.payout_details LIKE '%+7 914 883 64 06%' OR rr.payout_details LIKE '%+7 992 234 30 32%');
