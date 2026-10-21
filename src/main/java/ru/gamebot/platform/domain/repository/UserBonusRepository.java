package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.UserBonus;

public interface UserBonusRepository extends JpaRepository<UserBonus, Long> {

    boolean existsByUserIdAndBonusId(Long userId, Long bonusId);

    long countByBonusId(Long bonusId);

    Optional<UserBonus> findByUserIdAndBonusId(Long userId, Long bonusId);

    List<UserBonus> findByUserIdAndValidUntilAfter(Long userId, LocalDateTime now);
}
