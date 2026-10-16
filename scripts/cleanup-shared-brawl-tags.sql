-- ОДНОРАЗОВАЯ ЧИСТКА (решение владельца 2026-10-16, разбор scripts/check-shared-tags-impact.sql): аккаунты с общим игровым тегом Brawl Stars.
-- ЭТОТ СКРИПТ МЕНЯЕТ ДАННЫЕ. Запускать ТОЛЬКО ПОСЛЕ деплоя версии с withdrawal_frozen (иначе колонки нет) и один раз:
--   cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/cleanup-shared-brawl-tags.sql
-- Что делает:
--  1) помечает все 15 аккаунтов «подозрительными» (fraud_suspect);
--  2) блокирует трёх оставшихся из кластера #2VYRV29L28 (Итачи, Пьяный Итачи, Гоголь) БЕЗ конфискации баланса - блокировка обратима
--     (разблокировать: админка -> игрок, баланс останется на месте; если нужна конфискация - админ делает её сам кнопкой в карточке игрока);
--  3) замораживает вывод у «вторых» аккаунтов пар (Тайзер в разгрузке, zerottyy, DagLek, skvattt, Pidar): withdrawal_frozen = TRUE.
--     Снять заморозку: в боте от имени админа  /wdfreeze TelegramID off .
-- Безопасно перезапускать: все изменения идемпотентны.

UPDATE app_users SET fraud_suspect = TRUE WHERE telegram_id IN (
  5946155820, 8804822853, 7072660790, 8866769565, 8699455151, 8191516885, 6079430021, 8550235486, 8872504409,
  6794019183, 8815712347, 2140879930, 8386015998, 1833425865, 8754624249);

UPDATE app_users SET blocked = TRUE, block_reason = 'Мультиаккаунт: общий игровой аккаунт Brawl Stars (проверка 16.10.2026)', blocked_at = CURRENT_TIMESTAMP
WHERE telegram_id IN (8699455151, 8191516885, 6079430021) AND blocked = FALSE;

UPDATE app_users SET withdrawal_frozen = TRUE WHERE telegram_id IN (8804822853, 8866769565, 8815712347, 8386015998, 8754624249);

-- Проверка результата (15 строк): fraud_suspect = TRUE у всех, blocked у 5 из кластера, withdrawal_frozen у 5 «вторых»
SELECT u.telegram_id, u.nickname, u.fraud_suspect, u.blocked, u.withdrawal_frozen
FROM app_users u WHERE u.telegram_id IN (
  5946155820, 8804822853, 7072660790, 8866769565, 8699455151, 8191516885, 6079430021, 8550235486, 8872504409,
  6794019183, 8815712347, 2140879930, 8386015998, 1833425865, 8754624249)
ORDER BY u.telegram_id;
