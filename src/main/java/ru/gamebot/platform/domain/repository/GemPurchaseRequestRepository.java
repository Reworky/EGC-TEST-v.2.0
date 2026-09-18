package ru.gamebot.platform.domain.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.enums.GemPurchaseStatus;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.GemPurchaseRequest;

public interface GemPurchaseRequestRepository extends JpaRepository<GemPurchaseRequest, Long> {

    List<GemPurchaseRequest> findAllByStatusOrderByCreatedAtAsc(GemPurchaseStatus status);

    List<GemPurchaseRequest> findAllByUserOrderByCreatedAtDesc(AppUser user);
}
