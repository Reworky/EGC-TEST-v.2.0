package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.enums.SubmissionStatus;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.model.QuestSubmission;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.QuestRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuestService {

    private static final int WEEKLY_QUEST_TYPE_LIMIT = 3;
    private static final int COOLDOWN_HOURS = 24;
    private static final int REFERRAL_BONUS_PERCENT = 3;
    private static final int REFERRAL_DAYS_WINDOW = 14;

    private final QuestRepository questRepository;
    private final QuestSubmissionRepository questSubmissionRepository;
    private final AppUserRepository appUserRepository;
    private final UserService userService;
    private final HealthRatioService healthRatioService;
    private final SinkShopService sinkShopService;

    public List<Quest> findActiveQuests() {
        return questRepository.findAllByActiveTrueOrderByCreatedAtDesc();
    }

    public List<Quest> findActiveQuestsForUser(boolean isCouncilMember) {
        return findActiveQuests().stream()
                .filter(q -> !q.isCouncilOnly() || isCouncilMember)
                .toList();
    }

    public List<Quest> findActiveCouncilQuests() {
        return findActiveQuests().stream()
                .filter(Quest::isCouncilOnly)
                .toList();
    }

    public List<String> findActiveGameNames() {
        return findActiveQuests().stream()
                .map(Quest::getGameName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    public List<String> findAllGameNames() {
        return questRepository.findAll().stream()
                .map(Quest::getGameName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    public List<Quest> findByCategory(String category) {
        return findActiveQuests().stream()
                .filter(quest -> sameCategory(quest.getCategory(), category))
                .toList();
    }

    public List<Quest> findActiveByGameNameAndCategory(String gameName, String category) {
        return findActiveByGameName(gameName).stream()
                .filter(quest -> sameCategory(quest.getCategory(), category))
                .toList();
    }

    public List<Quest> findActiveByGameName(String gameName) {
        return findActiveQuests().stream()
                .filter(quest -> sameGame(quest.getGameName(), gameName))
                .sorted(Comparator.comparing(Quest::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public List<Quest> findAllByGameName(String gameName) {
        return questRepository.findAll().stream()
                .filter(quest -> sameGame(quest.getGameName(), gameName))
                .sorted(Comparator.comparing(Quest::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public List<Quest> findAllByGameNameAndCategory(String gameName, String category) {
        return findAllByGameName(gameName).stream()
                .filter(quest -> sameCategory(quest.getCategory(), category))
                .toList();
    }

    public List<Quest> findAll() {
        return questRepository.findAll();
    }

    public Quest getQuest(Long questId) {
        return questRepository.findById(questId)
                .orElseThrow(() -> new IllegalArgumentException("Квест не найден."));
    }

    @Transactional
    public Quest createQuest(Quest quest) {
        quest.setCreatedAt(LocalDateTime.now());
        quest.setActive(true);
        return questRepository.save(quest);
    }

    @Transactional
    public Quest save(Quest quest) {
        return questRepository.save(quest);
    }

    public QuestSubmission getLatestSubmission(AppUser user, Quest quest) {
        return questSubmissionRepository.findTopByUserAndQuestOrderByCreatedAtDesc(user, quest).orElse(null);
    }

    public boolean isSameQuestCooldownActive(AppUser user, Quest quest) {
        Optional<LocalDateTime> lastApproved = questSubmissionRepository
                .findLastApprovedDateByUserAndQuest(user, quest);
        return lastApproved.isPresent()
                && LocalDateTime.now().isBefore(lastApproved.get().plusHours(COOLDOWN_HOURS));
    }

    public boolean isCooldownActive(AppUser user, Quest quest) {
        Optional<LocalDateTime> lastApproved = questSubmissionRepository
                .findLastApprovedDateByUserAndGameAndCategory(user, quest.getGameName(), quest.getCategory());
        if (lastApproved.isPresent() && LocalDateTime.now().isBefore(lastApproved.get().plusHours(COOLDOWN_HOURS))) {
            if (sinkShopService.hasCooldownBypass(user, quest.getGameName())) {
                sinkShopService.consumeCooldownBypass(user, quest.getGameName());
                return false;
            }
            return true;
        }
        return false;
    }

    /** Возвращает сколько часов осталось до снятия кулдауна (0 = нет кулдауна) */
    public long getCooldownHoursLeft(AppUser user, Quest quest) {
        Optional<LocalDateTime> lastApproved = questSubmissionRepository
                .findLastApprovedDateByUserAndQuest(user, quest);
        if (lastApproved.isPresent()) {
            LocalDateTime until = lastApproved.get().plusHours(COOLDOWN_HOURS);
            if (LocalDateTime.now().isBefore(until)) {
                return Math.max(1, java.time.temporal.ChronoUnit.HOURS.between(LocalDateTime.now(), until));
            }
        }
        Optional<LocalDateTime> lastGame = questSubmissionRepository
                .findLastApprovedDateByUserAndGameAndCategory(user, quest.getGameName(), quest.getCategory());
        if (lastGame.isPresent()) {
            LocalDateTime until = lastGame.get().plusHours(COOLDOWN_HOURS);
            if (LocalDateTime.now().isBefore(until)) {
                return Math.max(1, java.time.temporal.ChronoUnit.HOURS.between(LocalDateTime.now(), until));
            }
        }
        return 0;
    }

    public long getWeeklyCompletionsOfType(AppUser user, Quest quest) {
        return questSubmissionRepository.countApprovedByUserAndGameAndCategorySince(
                user, quest.getGameName(), quest.getCategory(), LocalDateTime.now().minusWeeks(1));
    }

    @Transactional
    public QuestSubmission resetToDraft(QuestSubmission submission) {
        submission.setStatus(SubmissionStatus.DRAFT);
        submission.setMediaType(null);
        submission.setMediaFileId(null);
        submission.setExternalLink(null);
        submission.setUserComment(null);
        submission.setModeratorComment(null);
        submission.setUpdatedAt(LocalDateTime.now());
        return questSubmissionRepository.save(submission);
    }

    @Transactional
    public QuestSubmission createDraftSubmission(AppUser user, Quest quest) {
        QuestSubmission submission = new QuestSubmission();
        submission.setUser(user);
        submission.setQuest(quest);
        submission.setStatus(SubmissionStatus.DRAFT);
        submission.setCreatedAt(LocalDateTime.now());
        submission.setUpdatedAt(LocalDateTime.now());
        submission.setDisplayId(questSubmissionRepository.findMaxDisplayId() + 1);
        if (quest.getDurationDays() > 0) {
            submission.setExpiresAt(LocalDateTime.now().plusDays(quest.getDurationDays()));
        }
        return questSubmissionRepository.save(submission);
    }

    public boolean isExpired(QuestSubmission submission) {
        return submission.getExpiresAt() != null
                && LocalDateTime.now().isAfter(submission.getExpiresAt());
    }

    @Transactional
    public QuestSubmission submitReport(QuestSubmission submission, String mediaType, String fileId,
                                        String externalLink, String comment) {
        submission.setMediaType(mediaType);
        submission.setMediaFileId(fileId);
        submission.setExternalLink(externalLink);
        submission.setUserComment(comment);
        submission.setStatus(SubmissionStatus.PENDING);
        submission.setUpdatedAt(LocalDateTime.now());
        questSubmissionRepository.save(submission);

        checkFraudSuspect(submission.getUser());

        return submission;
    }

    private void checkFraudSuspect(AppUser user) {
        if (user.isFraudSuspect()) {
            return;
        }

        // Check success rate > 90%
        long totalReviewed = questSubmissionRepository.countReviewedByUser(user);
        if (totalReviewed >= 5) {
            long totalApproved = questSubmissionRepository.countApprovedByUser(user);
            double successRate = (double) totalApproved / totalReviewed;
            if (successRate > 0.9) {
                // Check interval < 10 seconds between recent pending submissions
                List<LocalDateTime> recentTimes = questSubmissionRepository.findRecentPendingSubmissionTimes(user);
                if (recentTimes.size() >= 2) {
                    long secondsBetween = java.time.temporal.ChronoUnit.SECONDS.between(
                            recentTimes.get(1), recentTimes.get(0));
                    if (secondsBetween < 10) {
                        user.setFraudSuspect(true);
                        appUserRepository.save(user);
                        log.warn("Fraud suspect flagged: userId={} successRate={} interval={}s",
                                user.getTelegramId(), successRate, secondsBetween);
                    }
                }
            }
        }
    }

    public QuestSubmission getSubmission(Long submissionId) {
        return questSubmissionRepository.findWithUserAndQuestById(submissionId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена."));
    }

    public List<QuestSubmission> getPendingSubmissions() {
        return questSubmissionRepository.findAllByStatusOrderByCreatedAtAsc(SubmissionStatus.PENDING);
    }

    public List<QuestSubmission> getUserSubmissions(AppUser user) {
        return questSubmissionRepository.findAllByUserOrderByCreatedAtDesc(user).stream()
                .filter(s -> s.getStatus() != SubmissionStatus.CANCELLED)
                .toList();
    }

    @Transactional
    public QuestSubmission approveSubmission(Long submissionId) {
        QuestSubmission submission = getSubmission(submissionId);
        if (submission.getStatus() == SubmissionStatus.APPROVED) {
            return submission;
        }
        submission.setStatus(SubmissionStatus.APPROVED);
        submission.setModeratorComment("Принято. Отличная работа!");
        submission.setUpdatedAt(LocalDateTime.now());

        AppUser user = submission.getUser();
        Quest quest = submission.getQuest();

        long baseCoins = quest.getRewardCoins();
        long adjustedCoins = healthRatioService.applyRatio(baseCoins);

        // 3.4 Antifaud: diminishing returns after 3 completions of same type per week
        LocalDateTime weekAgo = LocalDateTime.now().minusWeeks(1);
        long weeklyCount = questSubmissionRepository.countApprovedByUserAndGameAndCategorySince(
                user, quest.getGameName(), quest.getCategory(), weekAgo);
        if (weeklyCount >= WEEKLY_QUEST_TYPE_LIMIT) {
            adjustedCoins = adjustedCoins / 2;
        }

        // Apply XP boost
        long baseXp = quest.getRewardXp();
        int xpBoostPct = sinkShopService.getXpBoostPercent(user);
        long adjustedXp = baseXp + (baseXp * xpBoostPct / 100);

        // 3.5 3000 EXC bonus on first quest (before completedQuests increment)
        userService.grantFirstQuestReferralBonus(user);

        userService.addReward(user, adjustedXp, adjustedCoins);
        user.setCompletedQuests(user.getCompletedQuests() + 1);
        submission.setUser(user);
        questSubmissionRepository.save(submission);

        // 3.5 Referral bonus: 10% of EXC earned by referred in first 30 days
        grantReferralBonus(user, adjustedCoins);

        return submission;
    }

    private void grantReferralBonus(AppUser invitedUser, long earnedCoins) {
        Long referrerTelegramId = invitedUser.getReferredByTelegramId();
        if (referrerTelegramId == null) {
            return;
        }
        if (invitedUser.getCreatedAt() == null) {
            return;
        }
        long daysSinceJoin = ChronoUnit.DAYS.between(invitedUser.getCreatedAt(), LocalDateTime.now());
        if (daysSinceJoin > REFERRAL_DAYS_WINDOW) {
            return;
        }
        AppUser referrer = appUserRepository.findByTelegramId(referrerTelegramId).orElse(null);
        if (referrer == null) {
            return;
        }
        long bonus = Math.max(1, earnedCoins * REFERRAL_BONUS_PERCENT / 100);
        userService.addReward(referrer, 0, bonus);
    }

    @Transactional
    public QuestSubmission rejectSubmission(Long submissionId, String moderatorComment) {
        QuestSubmission submission = getSubmission(submissionId);
        submission.setStatus(SubmissionStatus.REJECTED);
        submission.setModeratorComment(moderatorComment);
        submission.setUpdatedAt(LocalDateTime.now());
        return questSubmissionRepository.save(submission);
    }

    @Transactional
    public QuestSubmission requestClarification(Long submissionId) {
        QuestSubmission submission = getSubmission(submissionId);
        submission.setStatus(SubmissionStatus.NEEDS_INFO);
        submission.setModeratorComment("Нужны уточнения. Пожалуйста, отправьте более понятный отчёт.");
        submission.setUpdatedAt(LocalDateTime.now());
        return questSubmissionRepository.save(submission);
    }

    @Transactional
    public QuestSubmission cancelSubmission(Long submissionId, AppUser user) {
        QuestSubmission submission = getSubmission(submissionId);
        if (!submission.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Нет доступа.");
        }
        if (submission.getStatus() == SubmissionStatus.APPROVED) {
            throw new IllegalArgumentException("Одобренный квест нельзя отменить.");
        }
        if (submission.getStatus() == SubmissionStatus.CANCELLED) {
            throw new IllegalArgumentException("Квест уже отменён.");
        }
        submission.setStatus(SubmissionStatus.CANCELLED);
        submission.setUpdatedAt(LocalDateTime.now());
        return questSubmissionRepository.save(submission);
    }

    public long countReviewedByUser(AppUser user) {
        return questSubmissionRepository.countReviewedByUser(user);
    }

    public long countApprovedByUser(AppUser user) {
        return questSubmissionRepository.countApprovedByUser(user);
    }

    public long countAllApproved() {
        return questSubmissionRepository.countAllApproved();
    }

    public long sumAllIssuedCoins() {
        return questSubmissionRepository.sumAllIssuedCoins();
    }

    public String topGameName() {
        List<String> names = questSubmissionRepository.findTopGameNames();
        return names.isEmpty() ? "—" : names.get(0);
    }

    public long pendingCount() {
        return questSubmissionRepository.countByStatus(SubmissionStatus.PENDING);
    }

    @Transactional
    public long deleteQuest(Long questId) {
        Quest quest = getQuest(questId);
        long submissions = questSubmissionRepository.countByQuest(quest);
        if (submissions > 0) {
            questSubmissionRepository.deleteAllByQuest(quest);
        }
        questRepository.delete(quest);
        return submissions;
    }

    public long countActiveDrafts(AppUser user) {
        return questSubmissionRepository.findAllByUserOrderByCreatedAtDesc(user).stream()
                .filter(s -> s.getStatus() == SubmissionStatus.DRAFT && !isExpired(s))
                .count();
    }

    private boolean sameGame(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.trim().equalsIgnoreCase(right.trim());
    }

    private boolean sameCategory(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return normalizeCategory(left).equalsIgnoreCase(normalizeCategory(right));
    }

    private String normalizeCategory(String value) {
        String normalized = value == null ? "" : value.trim();
        return switch (normalized.toLowerCase()) {
            case "быстрые", "легкие", "лёгкие", "лёгкие задания", "легкие задания" -> "Лёгкие";
            case "долгие", "сложные", "сложные задания" -> "Сложные";
            case "средние", "средние задания" -> "Средние";
            default -> normalized;
        };
    }
}
