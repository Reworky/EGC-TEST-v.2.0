package ru.gamebot.platform.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.model.AlertRule;
import ru.gamebot.platform.domain.model.PlatformSnapshot;
import ru.gamebot.platform.domain.repository.AlertRuleRepository;
import ru.gamebot.platform.domain.repository.PlatformSnapshotRepository;
import ru.gamebot.platform.event.AnalyticsAlertEvent;

/** Настраиваемые алерты раздела «Аналитика»: раз в сутки (01:10 UTC, после ежедневного снимка в 00:05) сравнивает сегодняшний
 *  снимок со снимком N дней назад и, если изменение по модулю не меньше порога, шлёт админам личное сообщение. Правила и
 *  метки «уже сработало» лежат в БД, самому планировщику состояние не нужно: после деплоя ничего лишнего не уйдёт (повтор
 *  правила блокируется на окно правила, а до появления правил нечего проверять). */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private record MetricDef(String label, Function<PlatformSnapshot, Long> f) {}

    private static final Map<String, MetricDef> METRICS = new LinkedHashMap<>();
    static {
        METRICS.put("users", new MetricDef("Всего игроков", PlatformSnapshot::getTotalUsers));
        METRICS.put("active7", new MetricDef("Активны за 7 дней", PlatformSnapshot::getActive7Days));
        METRICS.put("active30", new MetricDef("Активны за 30 дней", PlatformSnapshot::getActive30Days));
        METRICS.put("dau", new MetricDef("DAU", PlatformSnapshot::getDau));
        METRICS.put("mau", new MetricDef("MAU", PlatformSnapshot::getMau));
        METRICS.put("takers7", new MetricDef("Взяли квест за 7 дней", PlatformSnapshot::getQuestTakers7d));
        METRICS.put("done30", new MetricDef("Выполнено за 30 дней", PlatformSnapshot::getApprovedQuestsMonth));
        METRICS.put("pool", new MetricDef("Активных квестов", PlatformSnapshot::getActiveQuestsCount));
        METRICS.put("pending", new MetricDef("На модерации", PlatformSnapshot::getPendingSubmissions));
        METRICS.put("review", new MetricDef("Среднее время проверки, мин", PlatformSnapshot::getAvgReviewMin7d));
        METRICS.put("earned7", new MetricDef("Начислено EXC за 7 дней", PlatformSnapshot::getEarnedExc7d));
        METRICS.put("paid7", new MetricDef("Выплачено EXC за 7 дней", PlatformSnapshot::getPaidOutExc7d));
        METRICS.put("coins", new MetricDef("EXC на счетах", PlatformSnapshot::getTotalCoinsOnAccounts));
        METRICS.put("uptime", new MetricDef("Аптайм за 7 дней, ‰", PlatformSnapshot::getUptimePermille7d));
    }

    private final AlertRuleRepository ruleRepository;
    private final PlatformSnapshotRepository snapshotRepository;
    private final ApplicationEventPublisher eventPublisher;

    public static Map<String, String> metricLabels() {
        Map<String, String> m = new LinkedHashMap<>();
        METRICS.forEach((k, v) -> m.put(k, v.label()));
        return m;
    }

    public static String labelOf(String key) {
        MetricDef d = METRICS.get(key);
        return d == null ? key : d.label();
    }

    public List<AlertRule> list() {
        return ruleRepository.findAllByOrderByIdAsc();
    }

    public AlertRule create(String metricKey, int thresholdPercent, int days) {
        AlertRule r = new AlertRule();
        r.setMetricKey(metricKey);
        r.setThresholdPercent(thresholdPercent);
        r.setDays(days);
        return ruleRepository.save(r);
    }

    public void toggle(Long id) {
        ruleRepository.findById(id).ifPresent(r -> {
            r.setEnabled(!r.isEnabled());
            ruleRepository.save(r);
        });
    }

    public void delete(Long id) {
        ruleRepository.deleteById(id);
    }

    @Scheduled(cron = "0 10 1 * * *")
    public void check() {
        try {
            List<AlertRule> rules = ruleRepository.findAllByEnabledTrue();
            if (rules.isEmpty()) return;
            LocalDate today = LocalDate.now();
            Optional<PlatformSnapshot> cur = snapshotRepository.findFirstBySnapshotDateLessThanEqualOrderBySnapshotDateDesc(today);
            if (cur.isEmpty()) return;
            for (AlertRule r : rules) {
                MetricDef def = METRICS.get(r.getMetricKey());
                if (def == null) continue;
                Optional<PlatformSnapshot> prev = snapshotRepository
                        .findFirstBySnapshotDateLessThanEqualOrderBySnapshotDateDesc(today.minusDays(r.getDays()));
                if (prev.isEmpty()) continue;
                Long now = def.f().apply(cur.get());
                Long before = def.f().apply(prev.get());
                if (now == null || before == null || before == 0) continue;
                double change = (now - before) * 100.0 / Math.abs(before);
                if (Math.abs(change) < r.getThresholdPercent()) continue;
                if (r.getLastFiredAt() != null && r.getLastFiredAt().isAfter(LocalDateTime.now().minusDays(r.getDays()))) continue;
                r.setLastFiredAt(LocalDateTime.now());
                ruleRepository.save(r);
                String arrow = change > 0 ? "▲ +" : "▼ ";
                eventPublisher.publishEvent(new AnalyticsAlertEvent(this,
                        "🔔 <b>Алерт: " + def.label() + "</b>\n\nИзменение за " + r.getDays() + " дн.: <b>" + arrow
                                + String.format("%.1f", change) + "%</b> (" + before + " → " + now + ").\nПорог правила: ±" + r.getThresholdPercent() + "%."));
            }
        } catch (Exception e) {
            log.error("Analytics alert check failed", e);
        }
    }
}
