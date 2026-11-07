package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import ru.gamebot.platform.domain.model.AutoApprovalNotice;

public interface AutoApprovalNoticeRepository extends JpaRepository<AutoApprovalNotice, Long> {

    long countBySeenFalse();

    List<AutoApprovalNotice> findTop30ByOrderByCreatedAtDescIdDesc();

    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query("update AutoApprovalNotice n set n.seen = true where n.seen = false")
    int markAllSeen();

    /** Чистка: прочитанные записи старше границы не нужны (таблица иначе растёт бесконечно). */
    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query("delete from AutoApprovalNotice n where n.seen = true and n.createdAt < :before")
    int deleteSeenBefore(@org.springframework.data.repository.query.Param("before") LocalDateTime before);
}
