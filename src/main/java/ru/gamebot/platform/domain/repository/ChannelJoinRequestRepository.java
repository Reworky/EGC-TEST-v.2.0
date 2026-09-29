package ru.gamebot.platform.domain.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.ChannelJoinRequest;

public interface ChannelJoinRequestRepository extends JpaRepository<ChannelJoinRequest, Long> {

    Optional<ChannelJoinRequest> findFirstByTelegramUserIdAndChatIdAndStatus(Long telegramUserId, Long chatId, String status);

    long countByStatus(String status);

    List<ChannelJoinRequest> findAllByStatusOrderByCreatedAtAsc(String status);
}
