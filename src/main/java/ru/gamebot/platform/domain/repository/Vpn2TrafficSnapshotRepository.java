package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.Vpn2TrafficSnapshot;

public interface Vpn2TrafficSnapshotRepository extends JpaRepository<Vpn2TrafficSnapshot, Long> {

    /** Самый свежий снимок не позже границы (для расчёта трафика за сутки). */
    Optional<Vpn2TrafficSnapshot> findFirstByTakenAtBeforeOrderByTakenAtDesc(LocalDateTime before);

    @Modifying
    @Transactional
    @Query("delete from Vpn2TrafficSnapshot s where s.takenAt < :before")
    int deleteOlderThan(@Param("before") LocalDateTime before);
}
