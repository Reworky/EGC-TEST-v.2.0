package ru.gamebot.platform.domain.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.ClubGoal;

public interface ClubGoalRepository extends JpaRepository<ClubGoal, Long> {

    Optional<ClubGoal> findFirstByGoalTypeOrderByIdDesc(String goalType);

    List<ClubGoal> findAllByGoalTypeAndStatus(String goalType, String status);

    List<ClubGoal> findAllByChannelMessageIdAndStatus(Integer channelMessageId, String status);
}
