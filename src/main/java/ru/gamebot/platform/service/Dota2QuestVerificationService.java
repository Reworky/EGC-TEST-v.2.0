package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.model.QuestSubmission;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;
import ru.gamebot.platform.event.DotaQuestAutoVerifiedEvent;

/**
 * Автоматическая верификация квестов Dota 2 через официальный Steam Web API вместо ручного скриншота
 * с opendota.com. По образцу BrawlQuestVerificationService, но проще: все 12 существующих квестов
 * проверяют одну характеристику ЗА ОДИН МАТЧ (не накопление N совпадений) — квест засчитывается сразу,
 * как только среди матчей, сыгранных ПОСЛЕ взятия квеста, находится один подходящий под порог.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Dota2QuestVerificationService {

    private static final long BATCH_DELAY_MS = 180; // тот же паттерн, что в BrawlQuestVerificationService

    private final Dota2ApiService dota2ApiService;
    private final QuestSubmissionRepository questSubmissionRepository;
    private final AppUserRepository appUserRepository;
    private final QuestService questService;
    private final ApplicationEventPublisher eventPublisher;

    public record AccountLookupResult(boolean success, String error, Long accountId, int matchesFound) {}

    /** Принимает account_id напрямую, SteamID64, либо ссылку на профиль Steam (steamcommunity.com/profiles/<id>). */
    public AccountLookupResult lookupAccount(String rawInput) {
        if (!dota2ApiService.isEnabled()) {
            return new AccountLookupResult(false, "Привязка аккаунта временно недоступна. Попробуйте позже.", null, 0);
        }
        Long accountId = parseAccountId(rawInput);
        if (accountId == null) {
            return new AccountLookupResult(false, "Не удалось распознать account_id. Введите число (Friend ID из Dota 2) или ссылку на профиль Steam.", null, 0);
        }
        try {
            List<Dota2ApiService.MatchSummary> matches = dota2ApiService.fetchRecentMatches(accountId, null);
            if (matches.isEmpty()) {
                return new AccountLookupResult(false,
                        "Не найдено ни одного матча. Убедитесь, что в Dota 2 включена настройка "
                                + "«Expose Public Match Data» (Настройки → Опции → Социальное) и что сыгран хотя бы один матч.",
                        null, 0);
            }
            return new AccountLookupResult(true, null, accountId, matches.size());
        } catch (Dota2ApiService.Dota2TransientException e) {
            log.warn("Dota account lookup transient failure for accountId={}", accountId, e);
            return new AccountLookupResult(false, "Сервис Steam временно недоступен. Попробуйте ещё раз чуть позже.", null, 0);
        }
    }

    private Long parseAccountId(String rawInput) {
        String trimmed = rawInput.trim();
        if (trimmed.contains("/")) {
            String[] parts = trimmed.split("/");
            trimmed = parts[parts.length - 1].isBlank() ? parts[parts.length - 2] : parts[parts.length - 1];
        }
        try {
            long value = Long.parseLong(trimmed);
            return value >= 76561197960265728L ? Dota2ApiService.steamId64ToAccountId(value) : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Transactional
    public void linkAccount(AppUser user, long accountId) {
        user.setDotaAccountId(accountId);
        user.setDotaLinkedAt(LocalDateTime.now());
        appUserRepository.save(user);
    }

    /** Точка входа шедулера. Не @Transactional — последовательные сетевые вызовы, как у Brawl. */
    public void checkInProgressSubmissions() {
        List<QuestSubmission> pending = questSubmissionRepository.findInProgressDotaAutoVerify();
        for (QuestSubmission submission : pending) {
            try {
                checkOne(submission);
            } catch (Exception e) {
                log.warn("Dota auto-verify check failed for submission {}", submission.getId(), e);
            }
            sleepBetweenCalls();
        }
    }

    private void checkOne(QuestSubmission submission) throws Dota2ApiService.Dota2TransientException {
        Quest quest = submission.getQuest();
        long accountId = submission.getUser().getDotaAccountId();
        Long cursor = submission.getDotaLastProcessedMatchId();
        long sinceEpochSeconds = submission.getCreatedAt().toEpochSecond(ZoneOffset.UTC);

        List<Dota2ApiService.MatchSummary> matches = dota2ApiService.fetchRecentMatches(accountId, null);
        if (matches.isEmpty()) return;

        // Только матчи, сыгранные после взятия квеста, и ещё не обработанные (курсор — максимальный
        // уже виденный match_id; Dota match_id монотонно возрастает глобально, надёжнее сортировки по времени).
        List<Dota2ApiService.MatchSummary> newMatches = matches.stream()
                .filter(m -> m.startTime() >= sinceEpochSeconds)
                .filter(m -> cursor == null || m.matchId() > cursor)
                .sorted(java.util.Comparator.comparingLong(Dota2ApiService.MatchSummary::matchId))
                .toList();
        if (newMatches.isEmpty()) return;

        long newCursor = cursor != null ? cursor : 0L;
        boolean completed = false;
        for (Dota2ApiService.MatchSummary m : newMatches) {
            Optional<Dota2ApiService.MatchResult> details;
            try {
                details = dota2ApiService.fetchMatchDetails(m.matchId(), accountId);
            } catch (Dota2ApiService.Dota2TransientException e) {
                // Известный риск: GetMatchDetails периодически 500-ит после патчей — не двигаем курсор
                // мимо этого матча, следующий цикл поллера (через 10 минут) попробует снова.
                log.warn("Dota GetMatchDetails failed for matchId={}, will retry next cycle", m.matchId(), e);
                break;
            }
            newCursor = m.matchId();
            if (details.isEmpty()) continue;

            int value = extractValue(quest, details.get());
            updateBestValue(submission, quest, value);

            if (matchesTarget(quest, value)) {
                completed = true;
                break;
            }
        }
        submission.setDotaLastProcessedMatchId(newCursor);
        questSubmissionRepository.save(submission);

        if (completed) {
            completeSubmission(submission);
        }
    }

    private int extractValue(Quest quest, Dota2ApiService.MatchResult m) {
        return switch (quest.getDotaVerifyType()) {
            case KILLS -> m.kills();
            case ASSISTS -> m.assists();
            case DEATHS_MAX -> m.deaths();
            case GOLD -> (int) Math.min(Integer.MAX_VALUE, m.goldEarned());
            case HERO_LEVEL -> m.heroLevel();
            case DURATION_MINUTES -> m.durationSeconds() / 60;
        };
    }

    private boolean matchesTarget(Quest quest, int value) {
        int target = quest.getDotaTargetCount();
        return quest.getDotaVerifyType() == ru.gamebot.platform.domain.enums.DotaVerifyType.DEATHS_MAX
                ? value <= target
                : value >= target;
    }

    /** Для DEATHS_MAX "лучший" — минимум (меньше смертей = ближе к цели), для остальных — максимум. */
    private void updateBestValue(QuestSubmission submission, Quest quest, int value) {
        Integer current = submission.getDotaBestValue();
        boolean isBetter = current == null
                || (quest.getDotaVerifyType() == ru.gamebot.platform.domain.enums.DotaVerifyType.DEATHS_MAX
                        ? value < current
                        : value > current);
        if (isBetter) {
            submission.setDotaBestValue(value);
        }
    }

    private void completeSubmission(QuestSubmission submission) {
        QuestSubmission approved = questService.approveSubmission(submission.getId());
        eventPublisher.publishEvent(new DotaQuestAutoVerifiedEvent(this, approved.getId()));
    }

    private void sleepBetweenCalls() {
        try { Thread.sleep(BATCH_DELAY_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
