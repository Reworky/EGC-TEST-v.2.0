package ru.gamebot.platform.domain.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.PlatformSnapshot;

public interface PlatformSnapshotRepository extends JpaRepository<PlatformSnapshot, Long> {

    Optional<PlatformSnapshot> findBySnapshotDate(LocalDate date);

    List<PlatformSnapshot> findTop30ByOrderBySnapshotDateDesc();
}
