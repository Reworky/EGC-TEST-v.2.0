package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Базовая статистика EGC VPN для вкладки аналитики «VPN»: ключи по таблице vpn_accounts, покупки по журналу stars_purchases
 *  (item_type starsitem:VPN_MONTH и starsitem:VPN_MONTH_EXC). Выдача админом через /vpn в журнал Stars не попадает. */
@Service
@RequiredArgsConstructor
public class VpnStatsService {

    private static final String VPN_ITEMS = "('starsitem:VPN_MONTH', 'starsitem:VPN_MONTH_EXC')";

    private final jakarta.persistence.EntityManager entityManager;

    public record Stats(long activeNow, long activeTrial, long activePaid, long expiringSoon, long pending,
                        long trialsStarted, long trialsStartedPrev, long purchases, long purchasesPrev, long purchasesWithExc,
                        long renewals, long starsRevenue, long starsRevenuePrev, long trialUsersAll, long trialUsersPaid, long totalKeys) { }

    public Stats stats(LocalDateTime from, LocalDateTime to, LocalDateTime prevFrom, LocalDateTime prevTo) {
        LocalDateTime now = LocalDateTime.now();
        String running = "status = 'ACTIVE' AND expires_at > ?1";
        long activeNow = num("SELECT COUNT(*) FROM vpn_accounts WHERE " + running, now);
        long activeTrial = num("SELECT COUNT(*) FROM vpn_accounts WHERE " + running + " AND plan_type = 'TRIAL'", now);
        long activePaid = num("SELECT COUNT(*) FROM vpn_accounts WHERE " + running + " AND plan_type = 'PAID'", now);
        long expiringSoon = num("SELECT COUNT(*) FROM vpn_accounts WHERE " + running + " AND expires_at < ?2", now, now.plusDays(3));
        long pending = num("SELECT COUNT(*) FROM vpn_accounts WHERE status = 'PENDING'");
        long totalKeys = num("SELECT COUNT(*) FROM vpn_accounts");
        long trials = num("SELECT COUNT(*) FROM vpn_accounts WHERE trial_used_at >= ?1 AND trial_used_at < ?2", from, to);
        long trialsPrev = num("SELECT COUNT(*) FROM vpn_accounts WHERE trial_used_at >= ?1 AND trial_used_at < ?2", prevFrom, prevTo);
        String inPeriod = "FROM stars_purchases WHERE item_type IN " + VPN_ITEMS + " AND created_at >= ?1 AND created_at < ?2";
        long purchases = num("SELECT COUNT(*) " + inPeriod, from, to);
        long purchasesPrev = num("SELECT COUNT(*) " + inPeriod, prevFrom, prevTo);
        long withExc = num("SELECT COUNT(*) FROM stars_purchases WHERE item_type = 'starsitem:VPN_MONTH_EXC' AND created_at >= ?1 AND created_at < ?2", from, to);
        long renewals = num("SELECT COUNT(*) FROM stars_purchases p WHERE p.item_type IN " + VPN_ITEMS + " AND p.created_at >= ?1 AND p.created_at < ?2 "
                + "AND EXISTS (SELECT 1 FROM stars_purchases q WHERE q.telegram_id = p.telegram_id AND q.item_type IN " + VPN_ITEMS + " AND q.created_at < p.created_at)", from, to);
        long stars = num("SELECT COALESCE(SUM(stars_amount), 0) " + inPeriod, from, to);
        long starsPrev = num("SELECT COALESCE(SUM(stars_amount), 0) " + inPeriod, prevFrom, prevTo);
        long trialUsersAll = num("SELECT COUNT(*) FROM vpn_accounts WHERE trial_used_at IS NOT NULL");
        long trialUsersPaid = num("SELECT COUNT(*) FROM vpn_accounts a WHERE a.trial_used_at IS NOT NULL "
                + "AND EXISTS (SELECT 1 FROM stars_purchases p WHERE p.telegram_id = a.telegram_id AND p.item_type IN " + VPN_ITEMS + ")");
        return new Stats(activeNow, activeTrial, activePaid, expiringSoon, pending, trials, trialsPrev, purchases, purchasesPrev, withExc,
                renewals, stars, starsPrev, trialUsersAll, trialUsersPaid, totalKeys);
    }

    private long num(String sql, Object... params) {
        jakarta.persistence.Query q = entityManager.createNativeQuery(sql);
        for (int i = 0; i < params.length; i++) q.setParameter(i + 1, params[i]);
        Object r = q.getSingleResult();
        return r == null ? 0 : ((Number) r).longValue();
    }
}
