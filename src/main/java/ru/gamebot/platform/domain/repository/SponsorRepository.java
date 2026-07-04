package ru.gamebot.platform.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.Sponsor;

import java.util.List;

public interface SponsorRepository extends JpaRepository<Sponsor, Long> {
    List<Sponsor> findAllByOrderByCreatedAtDesc();
    List<Sponsor> findAllByActiveTrueOrderByCreatedAtDesc();
}
