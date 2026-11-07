-- Почему у игрока ограничен вывод: все условия вывода по одному аккаунту. Только чтение.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-withdrawal-limits-skvattt.sql
-- Ник в двух строках WHERE (сейчас skvattt). Сервер в UTC, Москва = +3 ч.
-- 1) условия: каждый столбец - возможная причина ограничения
SELECT u.telegram_id, u.telegram_username, u.coins,
  u.blocked, u.withdrawal_frozen,
  CASE WHEN u.phone_number IS NULL OR u.phone_number = '' THEN 'НЕТ телефона' ELSE 'есть' END AS phone,
  u.country, u.age,
  u.completed_quests, u.created_at AS registered_utc,
  u.xp,
  (CASE WHEN u.xp >= 75000 THEN 150000 WHEN u.xp >= 35000 THEN 100000 WHEN u.xp >= 15000 THEN 80000 WHEN u.xp >= 5000 THEN 50000 WHEN u.xp >= 1000 THEN 25000 ELSE 10000 END) AS rank_limit,
  (CASE WHEN u.withdrawal_year = YEAR(CURRENT_DATE) AND u.withdrawal_month = MONTH(CURRENT_DATE) THEN u.monthly_withdrawn_exc ELSE 0 END) AS withdrawn_this_month,
  u.partner_withdrawal_allowance_exc AS partner_allowance,
  u.egc_pass_active_until
FROM app_users u WHERE LOWER(REPLACE(COALESCE(u.telegram_username, ''), '@', '')) = 'skvattt';
-- 2) квест партнёра (ОТП) и выплата банка
SELECT s.id, s.status, s.created_at, s.updated_at, s.bonus_paid_at, q.title
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id JOIN app_users u ON u.id = s.user_id
WHERE q.game_name = 'ОТП Банк' AND LOWER(REPLACE(COALESCE(u.telegram_username, ''), '@', '')) = 'skvattt' ORDER BY s.created_at DESC;
-- 3) его заявки на вывод и награды: «1 заявка в сутки», сумма за всё время для порога 10 000 EXC
SELECT r.id, r.display_id, r.status, r.created_at, r.paid_at, i.title, i.category, COALESCE(r.paid_price_coins, i.price_coins) AS exc
FROM reward_requests r JOIN reward_items i ON i.id = r.reward_id JOIN app_users u ON u.id = r.user_id
WHERE LOWER(REPLACE(COALESCE(u.telegram_username, ''), '@', '')) = 'skvattt' ORDER BY r.created_at DESC LIMIT 15;
