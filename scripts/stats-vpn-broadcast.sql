-- Рассылка про EGC VPN: воронка по волнам и вариантам (отправлено, дошло, зашли в VPN-бот, взяли пробный, заплатили). Только читает базу.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-vpn-broadcast.sql
-- «Зашли в VPN-бот» и «взяли пробный» считаются по всем получателям журнала, даже если они заходили раньше рассылки; метки источника bc_a / bc_b / bc_w2 смотрите в разделе статистики VPN-бота.
SELECT l.campaign AS wave, l.variant,
  COUNT(*) AS sent,
  SUM(CASE WHEN l.delivered = TRUE THEN 1 ELSE 0 END) AS delivered,
  SUM(CASE WHEN u.telegram_id IS NOT NULL THEN 1 ELSE 0 END) AS opened_vpn_bot,
  SUM(CASE WHEN a.trial_used_at IS NOT NULL THEN 1 ELSE 0 END) AS took_trial,
  SUM(CASE WHEN EXISTS (SELECT 1 FROM vpn2_payments p WHERE p.telegram_id = l.telegram_id AND p.refunded_at IS NULL) THEN 1 ELSE 0 END) AS paid
FROM vpn_broadcast_log l
LEFT JOIN vpn2_users u ON u.telegram_id = l.telegram_id
LEFT JOIN vpn2_accounts a ON a.telegram_id = l.telegram_id
GROUP BY l.campaign, l.variant
ORDER BY l.campaign, l.variant;
SELECT 'источники в VPN-боте' AS metric, source_code, COUNT(*) AS n FROM vpn2_users WHERE source_code LIKE 'bc_%' GROUP BY source_code ORDER BY source_code;
