package ru.gamebot.platform.domain.repository;

import ru.gamebot.platform.domain.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.RewardRequest;

public interface RewardRequestRepository extends JpaRepository<RewardRequest, Long> {

    void deleteAllByUser(AppUser user);
}
