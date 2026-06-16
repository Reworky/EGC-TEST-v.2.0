package ru.gamebot.platform.domain.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.SupportAttachment;
import ru.gamebot.platform.domain.model.SupportTicket;

public interface SupportAttachmentRepository extends JpaRepository<SupportAttachment, Long> {

    List<SupportAttachment> findAllByTicketOrderByCreatedAtAsc(SupportTicket ticket);

    void deleteAllByTicket(SupportTicket ticket);
}
