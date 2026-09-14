package ru.gamebot.platform.domain.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.NudgeFeedback;

public interface NudgeFeedbackRepository extends JpaRepository<NudgeFeedback, Long> {

    long countByReasonCode(String reasonCode);

    List<NudgeFeedback> findTop20ByOrderByCreatedAtDesc();
}
