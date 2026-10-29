package ru.gamebot.platform.domain.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.PulseSnapshotEntry;

public interface PulseSnapshotEntryRepository extends JpaRepository<PulseSnapshotEntry, Long> {

    void deleteBySnapshotDate(LocalDate snapshotDate);

    /** Ближайшая запись метрики не позже указанной даты (для «значение неделей раньше»). */
    Optional<PulseSnapshotEntry> findFirstByMetricKeyAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(String metricKey, LocalDate date);

    /** Последняя запись метрики строго до указанной даты (предыдущий отчёт). */
    Optional<PulseSnapshotEntry> findFirstByMetricKeyAndSnapshotDateLessThanOrderBySnapshotDateDesc(String metricKey, LocalDate date);

    List<PulseSnapshotEntry> findAllByOrderBySnapshotDateAscSignalNoAscIdAsc();
}
