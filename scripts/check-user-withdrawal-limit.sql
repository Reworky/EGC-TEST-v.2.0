-- Почему у игрока «съелся» лимит вывода после отмены заявки: счётчик месяца против живых заявок. Только чтение.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-user-withdrawal-limit.sql
-- Ник игрока (без @) в четырёх строках WHERE: сейчас beyoundbbbirthday13. Сервер в UTC, Москва = +3 ч.
-- 1) счётчики игрока: сколько система считает выведенным за месяц и сколько на самом деле живых заявок (шаг 3)
SELECT u.telegram_id, u.telegram_username, u.coins, u.xp,
  (CASE WHEN u.xp >= 75000 THEN 150000 WHEN u.xp >= 35000 THEN 100000 WHEN u.xp >= 15000 THEN 80000 WHEN u.xp >= 5000 THEN 50000 WHEN u.xp >= 1000 THEN 25000 ELSE 10000 END) AS rank_limit,
  u.withdrawal_year, u.withdrawal_month,
  (CASE WHEN u.withdrawal_year = YEAR(CURRENT_DATE) AND u.withdrawal_month = MONTH(CURRENT_DATE) THEN u.monthly_withdrawn_exc ELSE 0 END) AS counter_withdrawn,
  u.monthly_shop_spent_exc AS counter_shop,
  u.partner_withdrawal_allowance_exc AS partner_allowance,
  u.egc_pass_active_until, u.withdrawal_frozen
FROM app_users u WHERE LOWER(REPLACE(COALESCE(u.telegram_username, ''), '@', '')) = 'beyoundbbbirthday13';
-- 2) все заявки на вывод и награды за последние 40 дней: статус, сумма, когда создана
SELECT r.id, r.display_id, r.status, r.created_at, r.paid_at, i.title, i.category, COALESCE(r.paid_price_coins, i.price_coins) AS exc
FROM reward_requests r JOIN reward_items i ON i.id = r.reward_id JOIN app_users u ON u.id = r.user_id
WHERE LOWER(REPLACE(COALESCE(u.telegram_username, ''), '@', '')) = 'beyoundbbbirthday13' AND r.created_at > DATEADD('DAY', -40, CURRENT_TIMESTAMP) ORDER BY r.created_at DESC;
-- 3) сколько заявок на вывод ЖИВЫХ (не отменены и не отклонены) в этом месяце: должно совпадать с counter_withdrawn из шага 1
SELECT COALESCE(SUM(COALESCE(r.paid_price_coins, i.price_coins)), 0) AS live_withdrawn_this_month
FROM reward_requests r JOIN reward_items i ON i.id = r.reward_id JOIN app_users u ON u.id = r.user_id
WHERE LOWER(REPLACE(COALESCE(u.telegram_username, ''), '@', '')) = 'beyoundbbbirthday13' AND i.category = 'Вывод' AND r.status NOT IN ('CANCELLED', 'REJECTED')
  AND YEAR(r.created_at) = YEAR(CURRENT_DATE) AND MONTH(r.created_at) = MONTH(CURRENT_DATE);
-- 4) движение EXC по его заявкам: возврат при отмене должен быть записью «SHOP_REFUND»
SELECT t.created_at, t.type, t.amount, t.description
FROM exc_transactions t JOIN app_users u ON u.id = t.user_id
WHERE LOWER(REPLACE(COALESCE(u.telegram_username, ''), '@', '')) = 'beyoundbbbirthday13' AND t.type IN ('WITHDRAWAL', 'SHOP_BUY', 'SHOP_REFUND') ORDER BY t.created_at DESC LIMIT 15;
