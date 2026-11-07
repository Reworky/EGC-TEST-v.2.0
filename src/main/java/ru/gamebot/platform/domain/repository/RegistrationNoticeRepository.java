package ru.gamebot.platform.domain.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import ru.gamebot.platform.domain.model.RegistrationNotice;

public interface RegistrationNoticeRepository extends JpaRepository<RegistrationNotice, Long> {

    long countBySeenFalse();

    List<RegistrationNotice> findTop30ByOrderByCreatedAtDescIdDesc();

    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query("update RegistrationNotice n set n.seen = true where n.seen = false")
    int markAllSeen();
}
