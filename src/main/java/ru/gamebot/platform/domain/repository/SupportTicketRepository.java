package ru.gamebot.platform.domain.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.enums.SupportTicketStatus;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.SupportTicket;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, Long> {

    @EntityGraph(attributePaths = {"user"})
    List<SupportTicket> findTop20ByUserOrderByUpdatedAtDesc(AppUser user);

    @EntityGraph(attributePaths = {"user"})
    List<SupportTicket> findAllByStatusInOrderByUpdatedAtDesc(List<SupportTicketStatus> statuses);

    @EntityGraph(attributePaths = {"user"})
    Optional<SupportTicket> findWithUserById(Long id);

    long countByStatusIn(List<SupportTicketStatus> statuses);

    List<SupportTicket> findAllByUser(AppUser user);

    void deleteAllByUser(AppUser user);
}
