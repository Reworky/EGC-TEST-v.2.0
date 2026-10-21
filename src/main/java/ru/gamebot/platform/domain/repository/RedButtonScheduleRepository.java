package ru.gamebot.platform.domain.repository;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.RedButtonSchedule;

public interface RedButtonScheduleRepository extends JpaRepository<RedButtonSchedule, Long> {

    List<RedButtonSchedule> findByStatusInOrderByPlannedAt(Collection<String> statuses);

    List<RedButtonSchedule> findTop8ByOrderByPlannedAtDesc();
}
