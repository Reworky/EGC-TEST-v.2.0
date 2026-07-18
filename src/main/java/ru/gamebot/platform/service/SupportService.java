package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.enums.SupportTicketStatus;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.SupportAttachment;
import ru.gamebot.platform.domain.model.SupportTicket;
import ru.gamebot.platform.domain.repository.SupportAttachmentRepository;
import ru.gamebot.platform.domain.repository.SupportTicketRepository;

@Service
@RequiredArgsConstructor
public class SupportService {

    private final SupportTicketRepository supportTicketRepository;
    private final SupportAttachmentRepository supportAttachmentRepository;

    @Transactional
    public SupportTicket getOrCreateActiveTicket(AppUser user, String firstMessage, String mediaGroupId) {
        return supportTicketRepository
                .findFirstByUserAndStatusInOrderByUpdatedAtDesc(user, List.of(SupportTicketStatus.OPEN, SupportTicketStatus.ANSWERED))
                .orElseGet(() -> createTicket(user, firstMessage, mediaGroupId));
    }

    @Transactional
    public SupportTicket createTicket(AppUser user, String initialMessage, String mediaGroupId) {
        SupportTicket ticket = new SupportTicket();
        ticket.setUser(user);
        ticket.setStatus(SupportTicketStatus.OPEN);
        ticket.setInitialMessage(initialMessage == null || initialMessage.isBlank() ? "Без текста" : initialMessage);
        ticket.setMediaGroupId(mediaGroupId);
        ticket.setCreatedAt(LocalDateTime.now());
        ticket.setUpdatedAt(LocalDateTime.now());
        return supportTicketRepository.save(ticket);
    }

    @Transactional
    public SupportAttachment addAttachment(SupportTicket ticket, boolean fromModerator, String mediaType, String fileId, String caption) {
        SupportAttachment attachment = new SupportAttachment();
        attachment.setTicket(ticket);
        attachment.setFromModerator(fromModerator);
        attachment.setMediaType(mediaType);
        attachment.setFileId(fileId);
        attachment.setCaption(caption);
        attachment.setCreatedAt(LocalDateTime.now());
        ticket.setUpdatedAt(LocalDateTime.now());
        supportTicketRepository.save(ticket);
        return supportAttachmentRepository.save(attachment);
    }

    public SupportTicket getTicket(Long ticketId) {
        return supportTicketRepository.findWithUserById(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка поддержки не найдена."));
    }

    public List<SupportTicket> getUserTickets(AppUser user) {
        return supportTicketRepository.findTop20ByUserOrderByUpdatedAtDesc(user);
    }

    public List<SupportTicket> getActiveTickets() {
        return supportTicketRepository.findAllByStatusInOrderByUpdatedAtDesc(
                List.of(SupportTicketStatus.OPEN, SupportTicketStatus.ANSWERED)
        );
    }

    public List<SupportAttachment> getAttachments(SupportTicket ticket) {
        return supportAttachmentRepository.findAllByTicketOrderByCreatedAtAsc(ticket);
    }

    @Transactional
    public SupportTicket markAnswered(Long ticketId, Long moderatorTelegramId, String replyText) {
        SupportTicket ticket = getTicket(ticketId);
        ticket.setStatus(SupportTicketStatus.ANSWERED);
        ticket.setLastModeratorTelegramId(moderatorTelegramId);
        ticket.setLastModeratorReply(replyText == null || replyText.isBlank() ? "Ответ отправлен вложением" : replyText);
        ticket.setUpdatedAt(LocalDateTime.now());
        return supportTicketRepository.save(ticket);
    }

    @Transactional
    public SupportTicket closeTicket(Long ticketId, Long moderatorTelegramId) {
        SupportTicket ticket = getTicket(ticketId);
        ticket.setStatus(SupportTicketStatus.CLOSED);
        ticket.setLastModeratorTelegramId(moderatorTelegramId);
        ticket.setClosedAt(LocalDateTime.now());
        ticket.setUpdatedAt(LocalDateTime.now());
        return supportTicketRepository.save(ticket);
    }

    public long activeTicketCount() {
        return supportTicketRepository.countByStatusIn(List.of(SupportTicketStatus.OPEN, SupportTicketStatus.ANSWERED));
    }
}
