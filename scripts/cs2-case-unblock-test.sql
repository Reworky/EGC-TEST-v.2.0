-- Снимает 30-дневный блок кейсов, поставленный за непринятый обмен (метка «[кейс не принят]» в комментарии отклонённых заявок на кейсы).
-- ВНИМАНИЕ: снимает блок у ВСЕХ игроков с такой меткой. Нужен после тестов автовыдачи на своём аккаунте. Применяется сразу, без перезапуска.
-- Запуск: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/cs2-case-unblock-test.sql
UPDATE reward_requests SET admin_comment = REPLACE(admin_comment, '[кейс не принят]', '[блок снят вручную]')
WHERE admin_comment LIKE '[кейс не принят]%'
  AND reward_id IN (SELECT id FROM reward_items WHERE purchase_group = 'cs2_case');
