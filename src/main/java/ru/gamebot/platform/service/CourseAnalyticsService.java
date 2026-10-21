package ru.gamebot.platform.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Цифры вкладки аналитики «Курс дня и красная кнопка» (этап 4 ТЗ, 21.10.2026): охват кнопки, активации, возврат на следующий день,
 * заявки с бонусом и без, расход бюджета, реакция на красную кнопку, проверка на фрод. Только чтение, нативные SQL по таблицам
 * daily_bonus / user_bonus / reward_requests / user_activity_days. Просмотры поста недоступны: Bot API не отдаёт счётчик просмотров канала.
 */
@Service
@RequiredArgsConstructor
public class CourseAnalyticsService {

    private final EntityManager entityManager;

    public record Report(long dailyPosts, long redPosts, long clicks, long activations, long activationsDaily, long activationsRed,
                         long activatingUsers, long activationsOfPublished, Double activationsPerDauPct, Double returnActivatedPct,
                         Double returnBasePct, long returnSample, long withdrawalsWithBonus, long withdrawalsWithout,
                         Double avgRubWith, Double avgRubWithout, long bonusPaidRub, long budgetTotalRub, long budgetUsedRub,
                         Double redReactionAvgMin, Double redReactionMedianMin, long suspectActivations, long bonusToSuspectsRub,
                         List<String> perBonusLines) { }

    @SuppressWarnings("unchecked")
    private List<Object[]> rows(String sql, Object... params) {
        Query q = entityManager.createNativeQuery(sql);
        for (int i = 0; i < params.length; i++) q.setParameter(i + 1, params[i]);
        List<Object[]> out = new ArrayList<>();
        for (Object r : (List<Object>) q.getResultList()) {
            out.add(r instanceof Object[] arr ? arr : new Object[]{r});
        }
        return out;
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private static LocalDate toDate(Object o) {
        if (o instanceof LocalDate d) return d;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof java.util.Date d) return new java.sql.Date(d.getTime()).toLocalDate();
        return LocalDate.parse(String.valueOf(o).substring(0, 10));
    }

    public Report report(LocalDateTime from, LocalDateTime to) {
        long dailyPosts = 0, redPosts = 0, clicks = 0, budgetTotal = 0, budgetUsed = 0;
        for (Object[] r : rows("SELECT type, COUNT(*), COALESCE(SUM(clicks), 0), COALESCE(SUM(budget_total_rub), 0), COALESCE(SUM(budget_used_rub), 0) "
                + "FROM daily_bonus WHERE published_at >= ?1 AND published_at < ?2 GROUP BY type", from, to)) {
            if (DailyBonusType.RED.equals(String.valueOf(r[0]))) redPosts += num(r[1]); else dailyPosts += num(r[1]);
            clicks += num(r[2]);
            budgetTotal += num(r[3]);
            budgetUsed += num(r[4]);
        }

        long activations = 0, actDaily = 0, actRed = 0;
        for (Object[] r : rows("SELECT b.type, COUNT(*) FROM user_bonus ub JOIN daily_bonus b ON b.id = ub.bonus_id "
                + "WHERE ub.activated_at >= ?1 AND ub.activated_at < ?2 GROUP BY b.type", from, to)) {
            long n = num(r[1]);
            activations += n;
            if (DailyBonusType.RED.equals(String.valueOf(r[0]))) actRed += n; else actDaily += n;
        }
        long users = rows("SELECT COUNT(DISTINCT user_id) FROM user_bonus WHERE activated_at >= ?1 AND activated_at < ?2", from, to)
                .stream().mapToLong(r -> num(r[0])).sum();
        long actOfPublished = rows("SELECT COUNT(*) FROM user_bonus ub JOIN daily_bonus b ON b.id = ub.bonus_id "
                + "WHERE b.published_at >= ?1 AND b.published_at < ?2", from, to).stream().mapToLong(r -> num(r[0])).sum();

        // Активации к DAU: по каждому дню с активациями - уникальные активировавшие / активные в журнале в тот же день, среднее по дням
        Map<LocalDate, Long> actByDay = new HashMap<>();
        for (Object[] r : rows("SELECT CAST(activated_at AS DATE), COUNT(DISTINCT user_id) FROM user_bonus WHERE activated_at >= ?1 AND activated_at < ?2 "
                + "GROUP BY CAST(activated_at AS DATE)", from, to)) {
            actByDay.put(toDate(r[0]), num(r[1]));
        }
        Map<LocalDate, Long> dauByDay = new HashMap<>();
        for (Object[] r : rows("SELECT activity_day, COUNT(DISTINCT user_id) FROM user_activity_days WHERE activity_day >= ?1 AND activity_day <= ?2 GROUP BY activity_day",
                from.toLocalDate(), to.toLocalDate())) {
            dauByDay.put(toDate(r[0]), num(r[1]));
        }
        double ratioSum = 0;
        int ratioDays = 0;
        for (Map.Entry<LocalDate, Long> e : actByDay.entrySet()) {
            long dau = dauByDay.getOrDefault(e.getKey(), 0L);
            if (dau > 0) { ratioSum += (double) e.getValue() / dau; ratioDays++; }
        }
        Double perDau = ratioDays == 0 ? null : ratioSum / ratioDays * 100.0;

        // Возврат на следующий день: из активировавших в день D (D+1 уже прошёл) - кто был активен в D+1; база - все активные в те же дни D
        Set<String> active = new HashSet<>();
        Map<LocalDate, Set<Long>> activeByDay = new HashMap<>();
        for (Object[] r : rows("SELECT user_id, activity_day FROM user_activity_days WHERE activity_day >= ?1 AND activity_day <= ?2",
                from.toLocalDate(), to.toLocalDate().plusDays(1))) {
            LocalDate d = toDate(r[1]);
            activeByDay.computeIfAbsent(d, k -> new HashSet<>()).add(num(r[0]));
            active.add(num(r[0]) + "|" + d);
        }
        LocalDate today = LocalDate.now();
        long actSample = 0, actReturned = 0, baseSample = 0, baseReturned = 0;
        Set<LocalDate> matured = new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (Object[] r : rows("SELECT DISTINCT CAST(activated_at AS DATE), user_id FROM user_bonus WHERE activated_at >= ?1 AND activated_at < ?2", from, to)) {
            LocalDate d = toDate(r[0]);
            if (!d.plusDays(1).isBefore(today)) continue; // следующий день ещё не закончился
            long uid = num(r[1]);
            if (!seen.add(uid + "|" + d)) continue;
            matured.add(d);
            actSample++;
            if (active.contains(uid + "|" + d.plusDays(1))) actReturned++;
        }
        for (LocalDate d : matured) {
            for (Long uid : activeByDay.getOrDefault(d, Collections.emptySet())) {
                baseSample++;
                if (active.contains(uid + "|" + d.plusDays(1))) baseReturned++;
            }
        }
        Double retAct = actSample == 0 ? null : actReturned * 100.0 / actSample;
        Double retBase = baseSample == 0 ? null : baseReturned * 100.0 / baseSample;

        // Заявки на вывод (рубли и TON; Stars бонуса не получают): с бонусом и без
        long wWith = 0, wWithout = 0, bonusPaid = 0;
        Double avgWith = null, avgWithout = null;
        for (Object[] r : rows("SELECT has_bonus, COUNT(*), AVG(rub), SUM(bonus) FROM ("
                + "SELECT CASE WHEN COALESCE(rr.bonus_amount_rub, 0) > 0 THEN 1 ELSE 0 END AS has_bonus, rr.fixed_rub_value AS rub, COALESCE(rr.bonus_amount_rub, 0) AS bonus "
                + "FROM reward_requests rr JOIN reward_items ri ON ri.id = rr.reward_id "
                + "WHERE ri.category = 'Вывод' AND COALESCE(ri.purchase_group, '') <> 'telegram_stars' AND rr.status NOT IN ('CANCELLED', 'REJECTED') "
                + "AND rr.created_at >= ?1 AND rr.created_at < ?2) t GROUP BY has_bonus", from, to)) {
            boolean with = num(r[0]) == 1;
            Double avg = r[2] == null ? null : ((Number) r[2]).doubleValue();
            if (with) { wWith = num(r[1]); avgWith = avg; bonusPaid = num(r[3]); }
            else { wWithout = num(r[1]); avgWithout = avg; }
        }

        // Реакция на красную кнопку: минуты от публикации до нажатия
        List<Double> reactions = new ArrayList<>();
        for (Object[] r : rows("SELECT DATEDIFF('SECOND', b.published_at, ub.activated_at) FROM user_bonus ub JOIN daily_bonus b ON b.id = ub.bonus_id "
                + "WHERE b.type = 'RED' AND b.published_at >= ?1 AND b.published_at < ?2", from, to)) {
            reactions.add(Math.max(0, num(r[0])) / 60.0);
        }
        Collections.sort(reactions);
        Double reactAvg = reactions.isEmpty() ? null : reactions.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        Double reactMed = reactions.isEmpty() ? null
                : (reactions.size() % 2 == 1 ? reactions.get(reactions.size() / 2)
                : (reactions.get(reactions.size() / 2 - 1) + reactions.get(reactions.size() / 2)) / 2.0);

        // Фрод: активации и выплаченный бонус у игроков, уже помеченных подозрительными или заблокированных
        long suspectAct = rows("SELECT COUNT(*) FROM user_bonus ub JOIN app_users u ON u.id = ub.user_id "
                + "WHERE ub.activated_at >= ?1 AND ub.activated_at < ?2 AND (u.fraud_suspect = TRUE OR u.blocked = TRUE)", from, to)
                .stream().mapToLong(r -> num(r[0])).sum();
        long suspectPaid = rows("SELECT COALESCE(SUM(rr.bonus_amount_rub), 0) FROM reward_requests rr JOIN app_users u ON u.id = rr.user_id "
                + "WHERE rr.created_at >= ?1 AND rr.created_at < ?2 AND COALESCE(rr.bonus_amount_rub, 0) > 0 "
                + "AND rr.status NOT IN ('CANCELLED', 'REJECTED') AND (u.fraud_suspect = TRUE OR u.blocked = TRUE)", from, to)
                .stream().mapToLong(r -> num(r[0])).sum();

        List<String> perBonus = new ArrayList<>();
        for (Object[] r : rows("SELECT b.id, b.type, b.percent, CAST(b.published_at AS DATE), b.clicks, "
                + "(SELECT COUNT(*) FROM user_bonus u WHERE u.bonus_id = b.id), b.budget_used_rub, b.budget_total_rub "
                + "FROM daily_bonus b WHERE b.published_at >= ?1 AND b.published_at < ?2 ORDER BY b.published_at DESC LIMIT 7", from, to)) {
            String kind = DailyBonusType.RED.equals(String.valueOf(r[1])) ? "🔴" : "📈";
            perBonus.add(kind + " " + String.format("%02d.%02d", toDate(r[3]).getDayOfMonth(), toDate(r[3]).getMonthValue()) + " +" + num(r[2]) + "%: нажатий "
                    + num(r[4]) + ", включили " + num(r[5]) + ", бюджет " + num(r[6]) + "/" + num(r[7]) + " ₽");
        }

        return new Report(dailyPosts, redPosts, clicks, activations, actDaily, actRed, users, actOfPublished, perDau, retAct, retBase, actSample,
                wWith, wWithout, avgWith, avgWithout, bonusPaid, budgetTotal, budgetUsed, reactAvg, reactMed, suspectAct, suspectPaid, perBonus);
    }

    /** Значения типа бонуса (совпадают с DailyBonus.DAILY / DailyBonus.RED), вынесены, чтобы не тянуть сущность в запросы. */
    private static final class DailyBonusType {
        static final String RED = ru.gamebot.platform.domain.model.DailyBonus.RED;
    }
}
