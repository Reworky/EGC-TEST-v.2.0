package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.QuestPackSchedule;
import ru.gamebot.platform.domain.model.QuestPackSwitchLog;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.QuestPackSwitchLogRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;

/**
 * Честное сравнение эффекта ротации пачек: игроки делятся по «основной игре» (где больше всего выполненных квестов
 * за 60 дней) на тех, чья основная игра сейчас ротируется (включено расписание), и контрольную группу — остальные.
 * DAU/MAU считаются тем же способом, что и в остальной аналитике (активность = lastActivityDate / lastBotActivityAt /
 * lastMiniAppOpenAt). Сравнивать нужно не «рост», а разницу между группами до и после старта ротации.
 */
@Service
@RequiredArgsConstructor
public class QuestPackAnalyticsService {

    private static final int PRIMARY_GAME_WINDOW_DAYS = 60;

    private final AppUserRepository userRepo;
    private final QuestSubmissionRepository submissionRepo;
    private final QuestPackSwitchLogRepository switchLogRepo;
    private final QuestPackScheduleService scheduleService;

    public record CohortStat(long size, long dau, long mau, long active7) {
        public double dauMauPercent() {
            return mau > 0 ? dau * 100.0 / mau : 0;
        }

        public double active7Percent() {
            return size > 0 ? active7 * 100.0 / size : 0;
        }
    }

    public record GameDelta(String gameName, boolean rotating, long last14, long prev14) {}

    public record Report(List<String> rotatingGames, LocalDateTime rotationSince,
                         CohortStat rotating, CohortStat control,
                         List<GameDelta> games, List<QuestPackSwitchLog> recentSwitches) {
        public boolean hasRotation() {
            return !rotatingGames.isEmpty();
        }
    }

    @Transactional(readOnly = true)
    public Report build() {
        List<QuestPackSchedule> enabled = scheduleService.allEnabled();
        Set<String> rotatingLower = new HashSet<>();
        List<String> rotatingNames = enabled.stream().map(QuestPackSchedule::getGameName).toList();
        LocalDateTime since = null;
        for (QuestPackSchedule s : enabled) {
            rotatingLower.add(s.getGameName().toLowerCase());
            if (s.getEnabledSince() != null && (since == null || s.getEnabledSince().isBefore(since))) {
                since = s.getEnabledSince();
            }
        }

        LocalDateTime now = LocalDateTime.now();
        // основная игра игрока = игра с максимумом одобренных квестов за окно (при равенстве — по алфавиту)
        Map<Long, String> primary = new HashMap<>();
        Map<Long, Long> best = new HashMap<>();
        for (Object[] r : submissionRepo.countApprovedByUserAndGameSince(now.minusDays(PRIMARY_GAME_WINDOW_DAYS))) {
            long userId = ((Number) r[0]).longValue();
            String game = (String) r[1];
            long n = ((Number) r[2]).longValue();
            Long cur = best.get(userId);
            if (cur == null || n > cur || (n == cur && game.compareToIgnoreCase(primary.get(userId)) < 0)) {
                best.put(userId, n);
                primary.put(userId, game);
            }
        }
        Set<Long> rotatingIds = new HashSet<>();
        Set<Long> controlIds = new HashSet<>();
        for (Map.Entry<Long, String> e : primary.entrySet()) {
            (rotatingLower.contains(e.getValue().toLowerCase()) ? rotatingIds : controlIds).add(e.getKey());
        }

        Set<Long> active1 = new HashSet<>(userRepo.findActiveUserIdsSince(now.minusDays(1).toLocalDate(), now.minusDays(1)));
        Set<Long> active7 = new HashSet<>(userRepo.findActiveUserIdsSince(now.minusDays(7).toLocalDate(), now.minusDays(7)));
        Set<Long> active30 = new HashSet<>(userRepo.findActiveUserIdsSince(now.minusDays(30).toLocalDate(), now.minusDays(30)));

        List<GameDelta> games = new java.util.ArrayList<>();
        Map<String, Long> last = new HashMap<>();
        Map<String, Long> prev = new HashMap<>();
        for (Object[] r : submissionRepo.countApprovedByGameBetween(now.minusDays(14), now)) {
            last.put((String) r[0], ((Number) r[1]).longValue());
        }
        for (Object[] r : submissionRepo.countApprovedByGameBetween(now.minusDays(28), now.minusDays(14))) {
            prev.put((String) r[0], ((Number) r[1]).longValue());
        }
        Set<String> allGames = new HashSet<>(last.keySet());
        allGames.addAll(prev.keySet());
        for (String g : allGames) {
            games.add(new GameDelta(g, rotatingLower.contains(g.toLowerCase()), last.getOrDefault(g, 0L), prev.getOrDefault(g, 0L)));
        }
        games.sort((a, b) -> Long.compare(b.last14(), a.last14()));

        return new Report(rotatingNames, since,
                stat(rotatingIds, active1, active7, active30), stat(controlIds, active1, active7, active30),
                games, switchLogRepo.findTop10ByOrderBySwitchedAtDesc());
    }

    private static CohortStat stat(Set<Long> cohort, Set<Long> a1, Set<Long> a7, Set<Long> a30) {
        long dau = cohort.stream().filter(a1::contains).count();
        long active7 = cohort.stream().filter(a7::contains).count();
        long mau = cohort.stream().filter(a30::contains).count();
        return new CohortStat(cohort.size(), dau, mau, active7);
    }
}
