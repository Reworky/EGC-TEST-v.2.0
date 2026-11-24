package ru.gamebot.platform.domain.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.ThreadsPostLog;

public interface ThreadsPostLogRepository extends JpaRepository<ThreadsPostLog, Long> {

    Optional<ThreadsPostLog> findByPostKey(String postKey);

    List<ThreadsPostLog> findByReplyPendingTrue();

    List<ThreadsPostLog> findByStatus(String status);

    long countByStatus(String status);
}
