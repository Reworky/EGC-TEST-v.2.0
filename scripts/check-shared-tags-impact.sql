-- Сколько «стоят» аккаунты с общим тегом Brawl Stars: по каждому такому аккаунту - квесты, баланс, флаги, выплаты. Только чтение.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/check-shared-tags-impact.sql
-- Как читать: строки сгруппированы по тегу (game_tag); paid_withdrawals - число выплаченных выводов аккаунта (APPROVED);
-- fraud_suspect / blocked - текущие флаги. Аккаунты с paid_withdrawals > 0 в группе - реальные деньги уже ушли.

SELECT UPPER(u.brawl_stars_tag) AS game_tag, u.telegram_id, u.nickname, u.completed_quests AS quests, u.coins AS balance_exc,
       u.fraud_suspect, u.blocked, CAST(u.created_at AS DATE) AS registered,
       (SELECT COUNT(*) FROM reward_requests r JOIN reward_items i ON i.id = r.reward_id
         WHERE r.user_id = u.id AND i.category = 'Вывод' AND r.status = 'APPROVED') AS paid_withdrawals
FROM app_users u
WHERE UPPER(u.brawl_stars_tag) IN (
    SELECT UPPER(brawl_stars_tag) FROM app_users WHERE brawl_stars_tag IS NOT NULL AND brawl_stars_tag <> ''
    GROUP BY UPPER(brawl_stars_tag) HAVING COUNT(*) > 1)
ORDER BY UPPER(u.brawl_stars_tag), u.created_at;
