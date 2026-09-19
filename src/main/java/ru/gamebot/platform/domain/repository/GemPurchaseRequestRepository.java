package ru.gamebot.platform.domain.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.gamebot.platform.domain.enums.GemPurchaseStatus;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.GemPurchaseRequest;

public interface GemPurchaseRequestRepository extends JpaRepository<GemPurchaseRequest, Long> {

    // user — @ManyToOne(LAZY), open-in-view выключен (см. application.yml) — без EntityGraph/JOIN FETCH
    // req.getUser() за пределами репозитория падает LazyInitializationException (инцидент 2026-09-19,
    // тот же класс бага, что и с потерянными Stars-платежами; см. RewardRequestRepository — тот же паттерн).
    @EntityGraph(attributePaths = {"user"})
    List<GemPurchaseRequest> findAllByStatusOrderByCreatedAtAsc(GemPurchaseStatus status);

    @EntityGraph(attributePaths = {"user"})
    List<GemPurchaseRequest> findAllByUserOrderByCreatedAtDesc(AppUser user);

    @Query("SELECT r FROM GemPurchaseRequest r JOIN FETCH r.user WHERE r.id = :id")
    Optional<GemPurchaseRequest> findWithUserById(@Param("id") Long id);

    @Query("SELECT COALESCE(MAX(r.displayId), 0) FROM GemPurchaseRequest r")
    long findMaxDisplayId();
}
