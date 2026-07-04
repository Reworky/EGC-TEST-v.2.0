package ru.gamebot.platform.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.Tournament;

import java.util.List;
import java.util.Optional;

public interface TournamentRepository extends JpaRepository<Tournament, Long> {
    List<Tournament> findAllByStatusOrderByCreatedAtDesc(Tournament.Status status);
    Optional<Tournament> findFirstByStatusOrderByCreatedAtDesc(Tournament.Status status);
    List<Tournament> findAllByOrderByCreatedAtDesc();
}
