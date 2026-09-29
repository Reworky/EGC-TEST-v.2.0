package ru.gamebot.platform.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Заявка на вступление в закрытый канал (@exgamingclub): хранится в БД, а не в памяти бота, поэтому
 *  переживает рестарт. Владелец решил 2026-09-29 не принимать заявки автоматически, а держать их
 *  "на согласование" - карточка с кнопками ✅/❌ уходит админам, решение принимает человек. */
@Getter
@Setter
@Entity
@Table(name = "channel_join_requests")
public class ChannelJoinRequest {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String DECLINED = "DECLINED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long telegramUserId;

    @Column(nullable = false)
    private Long chatId;

    @Column(length = 64)
    private String username;

    @Column(length = 128)
    private String firstName;

    @Column(length = 128)
    private String lastName;

    @Column(length = 1000)
    private String bio;

    @Column(nullable = false, length = 16)
    private String status = PENDING;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime decidedAt;
}
