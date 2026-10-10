package ru.gamebot.platform.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Вход человека в канал спонсорского квеста «подпишись на канал» (событие chat_member, бот - админ канала). Нужен, чтобы отличить НОВОГО подписчика
 *  (вступил после запуска квеста - награда положена, даже если он подписался по ссылке до нажатия «Взять») от того, кто был в канале раньше. */
@Getter
@Setter
@Entity
@Table(name = "channel_joins", indexes = @Index(name = "idx_channel_joins_user_chat", columnList = "telegramId,chatId"))
public class ChannelJoin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long telegramId;

    @Column(nullable = false, length = 64)
    private String chatId;

    @Column(nullable = false)
    private LocalDateTime joinedAt;
}
