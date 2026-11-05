-- Сколько стоит поднять курс вывода (Health Ratio = фонд выплат / (все EXC на балансах / 100)). Только читает базу.
-- Запуск на сервере: cd /root/gamebot && git pull && bash scripts/run-sql.sh scripts/stats-health-ratio-cost.sql
SELECT ratio, payout_pool_rub AS pool_rub, total_debt_exc AS debt_exc, (total_debt_exc / 100) AS debt_rub,
  ROUND(0.60 * (total_debt_exc / 100) - payout_pool_rub) AS need_rub_for_60,
  ROUND(0.80 * (total_debt_exc / 100) - payout_pool_rub) AS need_rub_for_80,
  ROUND(0.90 * (total_debt_exc / 100) - payout_pool_rub) AS need_rub_for_90,
  calculated_at
FROM health_ratio_snapshots ORDER BY calculated_at DESC LIMIT 1;

-- Куда реально уходит EXC: балансы по группам (активные за 30 дней и остальные), чтобы понять, сколько долга «живого»
SELECT grp, COUNT(*) AS players, SUM(coins) AS exc_total, ROUND(SUM(coins) / 100) AS exc_rub_nominal FROM (
  SELECT coins,
    CASE WHEN GREATEST(COALESCE(last_activity_date, DATE '2000-01-01'),
                       CAST(COALESCE(last_bot_activity_at, TIMESTAMP '2000-01-01 00:00:00') AS DATE),
                       CAST(COALESCE(last_mini_app_open_at, TIMESTAMP '2000-01-01 00:00:00') AS DATE)) >= DATEADD('DAY', -30, CURRENT_DATE)
         THEN 'активны 30 дн.' ELSE 'неактивны' END AS grp
  FROM app_users WHERE coins > 0
) x GROUP BY grp;
