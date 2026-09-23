package ru.gamebot.platform.domain.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.Squad;

public interface SquadRepository extends JpaRepository<Squad, Long> {

    Optional<Squad> findByInviteCode(String inviteCode);

    Optional<Squad> findByNameIgnoreCase(String name);

    List<Squad> findAllByNameContainingIgnoreCase(String namePart);

    boolean existsByNameIgnoreCase(String name);

    List<Squad> findAllByStatus(String status);
}
