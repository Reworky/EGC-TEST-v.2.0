package ru.gamebot.platform.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.Poll;

import java.time.LocalDateTime;
import java.util.List;

public interface PollRepository extends JpaRepository<Poll, Long> {
    List<Poll> findAllByClosedFalseOrderByCreatedAtDesc();
    List<Poll> findAllByClosedFalseAndClosesAtBefore(LocalDateTime threshold);
    List<Poll> findAllByOrderByCreatedAtDesc();
}
