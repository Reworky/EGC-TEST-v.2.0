-- Статистика пары мультиаккаунтов: «Тайзер в разгрузке» (8804822853) и аккаунт(ы) с тем же игровым тегом Brawl Stars / Clash. Только чтение.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-tag-pair-8804822853.sql
-- 1) оба аккаунта рядом: профиль, баланс, квесты, флаги, телефон, кто позвал, выплаты
SELECT u.telegram_id, u.telegram_username, u.nickname, u.country, u.age, CAST(u.created_at AS TIMESTAMP(0)) AS registered,
  u.last_activity_date, u.xp, u.coins, u.completed_quests, u.streak_days,
  u.brawl_stars_tag, u.phone_number IS NOT NULL AS has_phone, u.referred_by_telegram_id,
  u.fraud_suspect, u.withdrawal_frozen, u.blocked,
  (SELECT COUNT(*) FROM reward_requests r JOIN reward_items i ON i.id = r.reward_id WHERE r.user_id = u.id AND i.category = 'Вывод' AND r.status = 'APPROVED') AS paid_withdrawals,
  (SELECT COALESCE(SUM(COALESCE(r.paid_price_coins, i.price_coins)), 0) FROM reward_requests r JOIN reward_items i ON i.id = r.reward_id WHERE r.user_id = u.id AND i.category = 'Вывод' AND r.status = 'APPROVED') AS paid_exc
FROM app_users u
WHERE u.telegram_id = 8804822853
   OR (u.brawl_stars_tag IS NOT NULL AND u.brawl_stars_tag <> '' AND UPPER(u.brawl_stars_tag) = (SELECT UPPER(brawl_stars_tag) FROM app_users WHERE telegram_id = 8804822853))
   OR (u.clash_royale_tag IS NOT NULL AND u.clash_royale_tag <> '' AND UPPER(u.clash_royale_tag) = (SELECT UPPER(clash_royale_tag) FROM app_users WHERE telegram_id = 8804822853))
   OR (u.clash_of_clans_tag IS NOT NULL AND u.clash_of_clans_tag <> '' AND UPPER(u.clash_of_clans_tag) = (SELECT UPPER(clash_of_clans_tag) FROM app_users WHERE telegram_id = 8804822853))
ORDER BY u.created_at;
-- 2) квесты обоих: что одобрено, чтобы видеть, кто какие награды получил
SELECT u.telegram_id, u.nickname, q.title, s.status, CAST(s.created_at AS TIMESTAMP(0)) AS taken, CAST(s.updated_at AS TIMESTAMP(0)) AS last_update
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id JOIN app_users u ON u.id = s.user_id
WHERE u.telegram_id = 8804822853
   OR (u.brawl_stars_tag IS NOT NULL AND u.brawl_stars_tag <> '' AND UPPER(u.brawl_stars_tag) = (SELECT UPPER(brawl_stars_tag) FROM app_users WHERE telegram_id = 8804822853))
ORDER BY u.telegram_id, s.created_at;
