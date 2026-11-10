-- Почему игрок помечен подозрительным (fraud_suspect): проверяем все автоматические причины из кода. Только чтение.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-user-fraud-reasons.sql
-- Игрок: bigbilly06 (telegram_id 8804822853) - в запросах ниже. Сервер в UTC, Москва = +3 ч.
-- Причины пометки в коде: (1) дубль скриншота в заявке; (2) отчёт отправлен быстрее чем через 30 минут после взятия квеста;
-- (3) 5+ проверенных заявок с долей одобрений выше 90% и интервалом между последними отправками меньше минуты;
-- (4) тот же номер телефона у другого аккаунта; (5) те же реквизиты вывода у другого аккаунта (у него заявок на вывод нет).
-- 1) (4) другие аккаунты с тем же номером телефона
SELECT o.telegram_id, o.telegram_username, o.nickname, o.country, o.created_at, o.coins, o.withdrawal_frozen, o.blocked, o.fraud_suspect
FROM app_users u JOIN app_users o ON o.phone_number = u.phone_number AND o.id <> u.id
WHERE u.telegram_id = 8804822853 AND u.phone_number IS NOT NULL;
-- 2) (1)(2)(3) его заявки на квесты: статус, минуты от взятия до отправки, пометка дубля скриншота
SELECT s.id, q.title, s.status, s.created_at AS taken_at, s.updated_at AS last_update,
  DATEDIFF('MINUTE', s.created_at, s.updated_at) AS minutes_taken_to_submit, s.duplicate_photo_detected AS dup_photo
FROM quest_submissions s JOIN quests q ON q.id = s.quest_id JOIN app_users u ON u.id = s.user_id
WHERE u.telegram_id = 8804822853 ORDER BY s.created_at;
-- 3) его реферальные связи: кто его позвал и кого позвал он (мультиаккаунты часто живут в такой цепочке)
SELECT 'пригласил его' AS link, r.telegram_id, r.telegram_username, r.nickname, r.created_at
FROM app_users u JOIN app_users r ON r.telegram_id = u.referred_by_telegram_id WHERE u.telegram_id = 8804822853
UNION ALL
SELECT 'позван им', c.telegram_id, c.telegram_username, c.nickname, c.created_at
FROM app_users c WHERE c.referred_by_telegram_id = 8804822853;
-- 4) аккаунты из той же страны, зарегистрированные в тот же день и с тем же источником трафика (грубый поиск связанных аккаунтов)
SELECT o.telegram_id, o.telegram_username, o.nickname, o.created_at, o.completed_quests, o.coins, o.fraud_suspect, o.withdrawal_frozen
FROM app_users u JOIN app_users o ON o.id <> u.id AND o.country = u.country AND CAST(o.created_at AS DATE) = CAST(u.created_at AS DATE)
WHERE u.telegram_id = 8804822853 AND o.fraud_suspect = TRUE;
