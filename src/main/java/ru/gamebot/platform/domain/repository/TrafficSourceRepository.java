package ru.gamebot.platform.domain.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.TrafficSource;

public interface TrafficSourceRepository extends JpaRepository<TrafficSource, Long> {

    Optional<TrafficSource> findByCode(String code);

    List<TrafficSource> findAllByOrderByCreatedAtDesc();
}
