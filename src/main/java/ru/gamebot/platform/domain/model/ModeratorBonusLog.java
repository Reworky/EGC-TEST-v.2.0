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

/** Журнал бонусов, начисленных игрокам модераторами (ModeratorBonusService): кто, кому, сколько и за что. По нему же считается суточный
 *  лимит модератора. */
@Getter
@Setter
@Entity
@Table(name = "moderator_bonus_logs")
public class ModeratorBonusLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long moderatorTelegramId;

    @Column(nullable = false)
    private Long targetTelegramId;

    private long xp;
    private long exc;
    private long tickets;

    @Column(length = 300)
    private String comment;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
