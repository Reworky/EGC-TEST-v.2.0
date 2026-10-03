package ru.gamebot.platform.domain.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.QuestPackSchedule;

public interface QuestPackScheduleRepository extends JpaRepository<QuestPackSchedule, Long> {

    Optional<QuestPackSchedule> findByGameNameIgnoreCase(String gameName);

    List<QuestPackSchedule> findAllByEnabledTrueOrderByNextSwitchAtAsc();
}
