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

/** Запись об автоматически закрытом квесте (проверка по API игры или партнёрской сети) для модераторов.
 *  Раньше каждое закрытие приходило отдельным сообщением, теперь копится здесь и показывается в разделе
 *  модерации «✅ Автозакрытые квесты (N)». Данные скопированы на момент закрытия. */
@Getter
@Setter
@Entity
@Table(name = "auto_approval_notices")
public class AutoApprovalNotice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Номер отчёта «З-N». */
    private Long completionNumber;

    private Long playerTelegramId;

    private String playerNickname;

    private String playerUsername;

    @Column(length = 500)
    private String questTitle;

    private String gameName;

    /** Способ проверки: «проверка через Brawl Stars API» и т.п. */
    @Column(length = 200)
    private String method;

    private long awardedXp;

    private long awardedExc;

    private LocalDateTime createdAt;

    @Column(name = "seen", columnDefinition = "boolean default false")
    private boolean seen;
}
