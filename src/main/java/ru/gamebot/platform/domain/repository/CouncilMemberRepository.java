package ru.gamebot.platform.domain.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.CouncilMember;

public interface CouncilMemberRepository extends JpaRepository<CouncilMember, Long> {

    boolean existsByUser(AppUser user);

    Optional<CouncilMember> findByUser(AppUser user);

    long count();
}
