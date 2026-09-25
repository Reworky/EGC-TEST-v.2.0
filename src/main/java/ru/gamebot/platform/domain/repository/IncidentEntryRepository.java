package ru.gamebot.platform.domain.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.IncidentEntry;

public interface IncidentEntryRepository extends JpaRepository<IncidentEntry, Long> {

    List<IncidentEntry> findAllByStatusOrderByCreatedAtDesc(String status);

    long countByStatus(String status);
}
