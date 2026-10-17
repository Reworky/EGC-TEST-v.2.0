package ru.gamebot.platform.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.WeeklyGoalClaim;
import ru.gamebot.platform.domain.repository.WeeklyGoalClaimRepository;

/** «Цель недели» (2026-10-17): совершить действия за награду минимум в TARGET_DAYS разных дней недели (пн-вс) и получить награду.
 *  Активный день = день, когда игрок получил засчитанный квест, ежедневный бонус или ввёл код дня (просто открыть бота недостаточно).
 *  Дни считаются на лету по существующим таблицам, отдельного журнала нет. Награду за прошлую неделю можно забрать до конца вторника. */
@Service
@RequiredArgsConstructor
public class WeeklyGoalService {

    public static final int TARGET_DAYS = 4;
    public static final long REWARD_EXC = 200;
    public static final int REWARD_TICKETS = 1;

    private final EntityManager entityManager;
    private final WeeklyGoalClaimRepository claimRepository;
    private final UserService userService;

    public record Progress(int days, LocalDate weekStart, boolean todayActive) {}

    public static LocalDate weekStart(LocalDate d) {
        return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static LocalDate toLocalDate(Object o) {
        if (o instanceof LocalDate d) return d;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof java.util.Date d) return new java.sql.Date(d.getTime()).toLocalDate();
        return LocalDate.parse(String.valueOf(o).substring(0, 10));
    }

    @SuppressWarnings("unchecked")
    private void addDays(Set<LocalDate> into, String sql, Object... params) {
        Query q = entityManager.createNativeQuery(sql);
        for (int i = 0; i < params.length; i++) q.setParameter(i + 1, params[i]);
        for (Object row : (java.util.List<Object>) q.getResultList()) {
            if (row != null) into.add(toLocalDate(row));
        }
    }

    /** Дни, начиная с from (включительно), когда у игрока было действие за награду. */
    public Set<LocalDate> activeDays(Long userId, LocalDate from) {
        Set<LocalDate> days = new HashSet<>();
        LocalDateTime fromTs = from.atStartOfDay();
        addDays(days, "SELECT DISTINCT CAST(updated_at AS DATE) FROM quest_submissions WHERE user_id = ?1 AND status = 'APPROVED' AND updated_at >= ?2", userId, fromTs);
        addDays(days, "SELECT DISTINCT CAST(created_at AS DATE) FROM exc_transactions WHERE user_id = ?1 AND type = 'DAILY' AND created_at >= ?2", userId, fromTs);
        addDays(days, "SELECT claim_date FROM daily_code_claims WHERE user_id = ?1 AND claim_date >= ?2", userId, from);
        return days;
    }

    private static int countInRange(Set<LocalDate> days, LocalDate fromInclusive, LocalDate toExclusive) {
        int n = 0;
        for (LocalDate d : days) if (!d.isBefore(fromInclusive) && d.isBefore(toExclusive)) n++;
        return n;
    }

    public Progress progress(AppUser user) {
        LocalDate today = LocalDate.now();
        LocalDate start = weekStart(today);
        Set<LocalDate> days = activeDays(user.getId(), start);
        return new Progress(countInRange(days, start, start.plusWeeks(1)), start, days.contains(today));
    }

    /** Неделя, награду за которую игрок может забрать прямо сейчас: прошлая (до конца вторника) или текущая; empty - забирать нечего. */
    public Optional<LocalDate> claimableWeek(AppUser user) {
        LocalDate today = LocalDate.now();
        LocalDate cur = weekStart(today);
        LocalDate prev = cur.minusWeeks(1);
        Set<LocalDate> days = activeDays(user.getId(), prev);
        if (today.getDayOfWeek().getValue() <= DayOfWeek.TUESDAY.getValue()
                && countInRange(days, prev, cur) >= TARGET_DAYS && !claimRepository.existsByUserIdAndWeekStart(user.getId(), prev)) {
            return Optional.of(prev);
        }
        if (countInRange(days, cur, cur.plusWeeks(1)) >= TARGET_DAYS && !claimRepository.existsByUserIdAndWeekStart(user.getId(), cur)) {
            return Optional.of(cur);
        }
        return Optional.empty();
    }

    public enum ClaimStatus { OK, NOTHING_TO_CLAIM, NOT_REGISTERED }

    public record ClaimResult(ClaimStatus status, long exc, int tickets) {}

    @Transactional
    public ClaimResult claim(AppUser user) {
        if (!user.isRegistrationCompleted()) return new ClaimResult(ClaimStatus.NOT_REGISTERED, 0, 0);
        Optional<LocalDate> week = claimableWeek(user);
        if (week.isEmpty()) return new ClaimResult(ClaimStatus.NOTHING_TO_CLAIM, 0, 0);
        WeeklyGoalClaim claim = new WeeklyGoalClaim();
        claim.setUserId(user.getId());
        claim.setWeekStart(week.get());
        claim.setClaimedAt(LocalDateTime.now());
        try {
            claimRepository.saveAndFlush(claim); // уникальность (игрок, неделя): двойное нажатие не выдаст награду дважды
        } catch (DataIntegrityViolationException e) {
            return new ClaimResult(ClaimStatus.NOTHING_TO_CLAIM, 0, 0);
        }
        userService.addReward(user, 0, REWARD_EXC, REWARD_TICKETS, ExcTransactionService.WEEKLY_GOAL,
                "Цель недели: " + TARGET_DAYS + " активных дня (неделя с " + week.get() + ")");
        return new ClaimResult(ClaimStatus.OK, REWARD_EXC, REWARD_TICKETS);
    }

    public long claimsForWeek(LocalDate weekStart) {
        return claimRepository.countByWeekStart(weekStart);
    }
}
