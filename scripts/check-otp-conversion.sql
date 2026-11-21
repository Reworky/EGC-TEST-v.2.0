-- Кто из игроков мог сделать конверсию LeadGid «ОТП Карта - Транзакция» (оффер 6626, конверсия 4661993939, 15.11.2026 21:15:30, выплата 1 720 ₽, «На проверке»). Только чтение.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-otp-conversion.sql
-- Важно: ссылка в боте не содержит ID игрока (метки aff_sub только источник/кампания/объявление), поэтому «ID клиента» и «ID клика» из LeadGid сопоставить с игроком напрямую нельзя.
-- Ищем по датам: время в LeadGid обычно московское, время бота UTC+7, поэтому дата транзакции 15.11 (МСК) может быть 15.11 или 16.11 в данных бота.
-- 1) Кандидаты: заявки на квест ОТП, где игрок отметил оплату покупки 14-17.11.2026 (purchase_reported_on) или банк уже отмечен выплаченным.
SELECT u.telegram_id, u.telegram_username, u.nickname, u.country, u.traffic_source_code AS source,
       s.id AS submission, s.status, s.mission_step AS step,
       s.created_at AS taken_at, s.card_activated_on AS activated_on, s.purchase_reported_on AS purchase_on,
       s.bonus_paid_at, s.updated_at
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id JOIN app_users u ON u.id = s.user_id
WHERE q.game_name IN ('Банк-партнёр', 'ОТП Банк')
  AND (s.purchase_reported_on BETWEEN DATE '2026-11-14' AND DATE '2026-11-17' OR s.bonus_paid_at IS NOT NULL)
ORDER BY s.purchase_reported_on, s.created_at;
-- 2) Если строк нет или несколько: все, кто взял квест ОТП до 16.11.2026 и дошёл хотя бы до даты активации карты (шаг 2 и дальше).
SELECT u.telegram_id, u.telegram_username, u.nickname, s.id AS submission, s.status, s.mission_step AS step,
       s.created_at AS taken_at, s.card_activated_on AS activated_on, s.purchase_reported_on AS purchase_on
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id JOIN app_users u ON u.id = s.user_id
WHERE q.game_name IN ('Банк-партнёр', 'ОТП Банк') AND s.created_at < TIMESTAMP '2026-11-16 12:00:00'
  AND (s.card_activated_on IS NOT NULL OR s.mission_step >= 2)
ORDER BY s.created_at;
-- 3) Сколько человек вообще брали квест ОТП до 16.11 (если один-два, ответ очевиден; если ни одного, конверсию сделал кто-то не из квеста: канал, реклама, прямой переход на витрину).
SELECT COUNT(DISTINCT s.user_id) AS took_before_16_nov
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id
WHERE q.game_name IN ('Банк-партнёр', 'ОТП Банк') AND s.created_at < TIMESTAMP '2026-11-16 12:00:00';
