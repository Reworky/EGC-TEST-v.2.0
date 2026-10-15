package ru.gamebot.platform.domain.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.TrafficSpendEntry;

public interface TrafficSpendEntryRepository extends JpaRepository<TrafficSpendEntry, Long> {

    List<TrafficSpendEntry> findAllByOrderByFromDateAsc();
}
