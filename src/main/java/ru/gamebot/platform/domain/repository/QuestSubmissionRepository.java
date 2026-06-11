package ru.gamebot.platform.domain.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.enums.SubmissionStatus;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.model.QuestSubmission;

public interface QuestSubmissionRepository extends JpaRepository<QuestSubmission, Long> {

    List<QuestSubmission> findAllByStatusOrderByCreatedAtAsc(SubmissionStatus status);

    List<QuestSubmission> findAllByUserOrderByCreatedAtDesc(AppUser user);

    Optional<QuestSubmission> findTopByUserAndQuestOrderByCreatedAtDesc(AppUser user, Quest quest);

    long countByStatus(SubmissionStatus status);
}
