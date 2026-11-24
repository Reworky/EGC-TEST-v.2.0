-- Диагностика лимита вывода одного игрока (только чтение). Игрок: telegram_id 5312667786 (@v_l_a_d_i_k0); для другого - заменить число во всех строках ниже.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-withdrawal-limit-player.sql

-- 1) Состояние лимита: XP (определяет базовый лимит), Pass, разовый партнёрский запас, счётчики месяца.
--    Базовый лимит по XP: <1000 = 10 000; 1000+ = 25 000; 5000+ = 50 000. Pass даёт +10 000 пока активен.
--    withdrawal_month/year - за какой месяц счётчик; в новом месяце обнуляется сам при первом обращении игрока.
SELECT u.id, u.telegram_id, u.xp, u.coins, u.egc_pass_active_until,
       u.partner_withdrawal_allowance_exc AS partner_allowance,
       u.monthly_withdrawn_exc AS withdrawn_this_month, u.monthly_shop_spent_exc AS shop_spent_this_month,
       u.withdrawal_month, u.withdrawal_year
FROM app_users u
WHERE u.telegram_id = 5312667786;

-- 2) Все выводы игрока (кроме отменённых): когда, сколько, статус.
SELECT rr.id, ri.title, rr.status, rr.paid_price_coins, rr.created_at, rr.paid_at, rr.from_sponsor
FROM reward_requests rr
JOIN reward_items ri ON ri.id = rr.reward_id
JOIN app_users u ON u.id = rr.user_id
WHERE u.telegram_id = 5312667786 AND ri.category = 'Вывод' AND rr.status NOT IN ('CANCELLED')
ORDER BY rr.created_at DESC LIMIT 20;

-- 3) Когда у игрока был Pass и откуда брался разовый запас (покупки Pass, бонусы ОТП/охоты за кодами в журнале EXC).
SELECT t.created_at, t.type, t.amount, t.description
FROM exc_transactions t
JOIN app_users u ON u.id = t.user_id
WHERE u.telegram_id = 5312667786
  AND (LOWER(t.description) LIKE '%pass%' OR LOWER(t.description) LIKE '%лимит%' OR LOWER(t.description) LIKE '%бонус%' OR LOWER(t.description) LIKE '%код%' OR LOWER(t.description) LIKE '%парт%')
ORDER BY t.created_at DESC LIMIT 30;
