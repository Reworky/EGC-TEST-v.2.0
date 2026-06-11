package ru.gamebot.platform.service;

import java.time.LocalDateTime;
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

    public List<Quest> findActiveQuests() {
        return questRepository.findAllByActiveTrueOrderByCreatedAtDesc();
    }

    public List<Quest> findByCategory(String category) {
        return questRepository.findAllByActiveTrueAndCategoryIgnoreCaseOrderByCreatedAtDesc(category);
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
        return questSubmissionRepository.findById(submissionId)
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
        userService.addReward(user, submission.getQuest().getRewardXp(), submission.getQuest().getRewardCoins());
        user.setCompletedQuests(user.getCompletedQuests() + 1);
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
}
