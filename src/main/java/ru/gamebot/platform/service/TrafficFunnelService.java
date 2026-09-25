package ru.gamebot.platform.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.TrafficSource;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;
import ru.gamebot.platform.domain.repository.TrafficSourceRepository;

/** Воронка по каждому источнику закупа: зашли → профиль → активация → 1-й квест → 2-й квест → живы на 7-й день
 *  + расход и цена за шаг воронки. Считается в Java по двум лёгким выборкам (игроки с меткой источника и их
 *  одобренные квесты), без правок схемы: метка trafficSourceCode ставится один раз при первом /start. */
@Service
@RequiredArgsConstructor
public class TrafficFunnelService {

    /** Игрок «созрел» для оценки удержания, когда с регистрации прошло не меньше стольких дней. */
    private static final int MATURITY_DAYS = 7;

    private final TrafficSourceRepository trafficSourceRepository;
    private final AppUserRepository appUserRepository;
    private final QuestSubmissionRepository questSubmissionRepository;

    public record SourceFunnel(TrafficSource source, long started, long profiled, long activated,
                               long firstQuest, long secondQuest, long matured, long alive7, long excPaid) {

        /** Цена шага воронки в ₽; null, если расход не указан или шаг ещё пуст. */
        public Long costPer(long steps) {
            if (source.getSpendRub() <= 0 || steps <= 0) return null;
            return Math.round((double) source.getSpendRub() / steps);
        }
    }

    @Transactional(readOnly = true)
    public List<SourceFunnel> compute() {
        Map<String, long[]> acc = new HashMap<>(); // 0 started,1 profiled,2 activated,3 first,4 second,5 matured,6 alive7,7 exc
        Map<Long, String> codeByUser = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();

        for (Object[] r : appUserRepository.findTrafficSourceUsersForFunnel()) {
            String code = (String) r[0];
            Long userId = ((Number) r[1]).longValue();
            LocalDateTime createdAt = (LocalDateTime) r[2];
            boolean profiled = Boolean.TRUE.equals(r[3]);
            boolean activated = Boolean.TRUE.equals(r[4]);
            LocalDate lastDate = (LocalDate) r[5];
            LocalDateTime lastBot = (LocalDateTime) r[6];
            LocalDateTime lastApp = (LocalDateTime) r[7];

            long[] a = acc.computeIfAbsent(code, k -> new long[8]);
            codeByUser.put(userId, code);
            a[0]++;
            if (profiled) a[1]++;
            if (activated) a[2]++;

            if (createdAt != null && !createdAt.isAfter(now.minusDays(MATURITY_DAYS))) {
                a[5]++;
                LocalDateTime last = latest(lastDate == null ? null : lastDate.atStartOfDay(), lastBot, lastApp);
                if (last != null && !last.isBefore(createdAt.plusDays(MATURITY_DAYS))) a[6]++;
            }
        }

        for (Object[] r : questSubmissionRepository.countApprovedPerTrafficUser()) {
            String code = codeByUser.get(((Number) r[0]).longValue());
            if (code == null) continue;
            long[] a = acc.get(code);
            long approved = ((Number) r[1]).longValue();
            if (approved >= 1) a[3]++;
            if (approved >= 2) a[4]++;
            a[7] += ((Number) r[2]).longValue();
        }

        List<SourceFunnel> result = new ArrayList<>();
        for (TrafficSource ts : trafficSourceRepository.findAll()) {
            long[] a = acc.getOrDefault(ts.getCode(), new long[8]);
            result.add(new SourceFunnel(ts, a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7]));
        }
        return result;
    }

    public SourceFunnel forSource(TrafficSource ts) {
        for (SourceFunnel f : compute()) {
            if (f.source().getId().equals(ts.getId())) return f;
        }
        return new SourceFunnel(ts, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private static LocalDateTime latest(LocalDateTime... values) {
        LocalDateTime best = null;
        for (LocalDateTime v : values) {
            if (v != null && (best == null || v.isAfter(best))) best = v;
        }
        return best;
    }
}
