package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.enums.SubmissionStatus;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.model.QuestSubmission;
import ru.gamebot.platform.domain.repository.QuestRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;

@Service
@RequiredArgsConstructor
public class QuestService {

    private final QuestRepository questRepository;
    private final QuestSubmissionRepository questSubmissionRepository;
    private final UserService userService;
    private final HealthRatioService healthRatioService;

    public List<Quest> findActiveQuests() {
        return questRepository.findAllByActiveTrueOrderByCreatedAtDesc();
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

    @Transactional
    public QuestSubmission createDraftSubmission(AppUser user, Quest quest) {
        QuestSubmission submission = new QuestSubmission();
        submission.setUser(user);
        submission.setQuest(quest);
        submission.setStatus(SubmissionStatus.DRAFT);
        submission.setCreatedAt(LocalDateTime.now());
        submission.setUpdatedAt(LocalDateTime.now());
        return questSubmissionRepository.save(submission);
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
        return questSubmissionRepository.save(submission);
    }

    public QuestSubmission getSubmission(Long submissionId) {
        return questSubmissionRepository.findWithUserAndQuestById(submissionId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена."));
    }

    public List<QuestSubmission> getPendingSubmissions() {
        return questSubmissionRepository.findAllByStatusOrderByCreatedAtAsc(SubmissionStatus.PENDING);
    }

    public List<QuestSubmission> getUserSubmissions(AppUser user) {
        return questSubmissionRepository.findAllByUserOrderByCreatedAtDesc(user);
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
        long adjustedCoins = healthRatioService.applyRatio(submission.getQuest().getRewardCoins());
        userService.addReward(user, submission.getQuest().getRewardXp(), adjustedCoins);
        user.setCompletedQuests(user.getCompletedQuests() + 1);
        submission.setUser(user);
        return questSubmissionRepository.save(submission);
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
            case "быстрые", "легкие" -> "Легкие";
            case "долгие", "сложные" -> "Сложные";
            case "средние" -> "Средние";
            default -> normalized;
        };
    }
}
