-- Заявки на кейсы CS2 (автовыдача через market.csgo.com): последние 15, статус и комментарий. Только чтение.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-cs2-case-requests.sql
SELECT r.id AS request_id, r.display_id, i.title, r.status, r.paid_price_coins AS paid_exc, r.created_at, r.paid_at, r.admin_comment
FROM reward_requests r JOIN reward_items i ON i.id = r.reward_id
WHERE i.purchase_group = 'cs2_case'
ORDER BY r.id DESC LIMIT 15;
