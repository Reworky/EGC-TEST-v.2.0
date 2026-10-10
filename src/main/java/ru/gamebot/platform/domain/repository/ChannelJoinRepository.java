package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.ChannelJoin;

public interface ChannelJoinRepository extends JpaRepository<ChannelJoin, Long> {

    boolean existsByTelegramIdAndChatIdAndJoinedAtAfter(Long telegramId, String chatId, LocalDateTime after);
}
