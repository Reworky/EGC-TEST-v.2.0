package ru.gamebot.platform.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import ru.gamebot.platform.domain.enums.ScheduledBroadcastStatus;

@Getter
@Setter
@Entity
@Table(name = "scheduled_broadcasts")
public class ScheduledBroadcast {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 3000)
    private String text;

    private String photoFileId;

    @Column(length = 1000)
    private String caption;

    private LocalDateTime scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ScheduledBroadcastStatus status;

    private Long createdByTelegramId;
    private LocalDateTime createdAt;
    private LocalDateTime sentAt;

    @Column(columnDefinition = "integer default 0")
    private int deliveredCount;
}
