package ru.gamebot.platform.domain.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.UserPrize;

public interface UserPrizeRepository extends JpaRepository<UserPrize, Long> {

    List<UserPrize> findByUserIdAndStatusOrderByCreatedAtAscIdAsc(Long userId, String status);

    Optional<UserPrize> findByIdAndUserId(Long id, Long userId);

    long countByUserIdAndStatus(Long userId, String status);
}
