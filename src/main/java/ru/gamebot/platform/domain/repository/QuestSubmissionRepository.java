package ru.gamebot.platform.domain.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.enums.SubmissionStatus;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.model.QuestSubmission;

public interface QuestSubmissionRepository extends JpaRepository<QuestSubmission, Long> {

    @EntityGraph(attributePaths = {"user", "quest"})
    List<QuestSubmission> findAllByStatusOrderByCreatedAtAsc(SubmissionStatus status);

    @EntityGraph(attributePaths = {"user", "quest"})
    List<QuestSubmission> findAllByUserOrderByCreatedAtDesc(AppUser user);

    @EntityGraph(attributePaths = {"user", "quest"})
    Optional<QuestSubmission> findTopByUserAndQuestOrderByCreatedAtDesc(AppUser user, Quest quest);

    @EntityGraph(attributePaths = {"user", "quest"})
    Optional<QuestSubmission> findWithUserAndQuestById(Long id);

    long countByStatus(SubmissionStatus status);

    long countByQuest(Quest quest);

    void deleteAllByUser(AppUser user);

    void deleteAllByQuest(Quest quest);
}
