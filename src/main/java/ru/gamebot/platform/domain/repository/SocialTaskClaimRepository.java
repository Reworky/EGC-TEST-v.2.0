package ru.gamebot.platform.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.SocialTaskClaim;

import java.util.List;

public interface SocialTaskClaimRepository extends JpaRepository<SocialTaskClaim, Long> {
    boolean existsByUserAndTaskCode(AppUser user, String taskCode);

    @Query("SELECT c.taskCode FROM SocialTaskClaim c WHERE c.user = :user")
    List<String> findTaskCodesByUser(@Param("user") AppUser user);
}
