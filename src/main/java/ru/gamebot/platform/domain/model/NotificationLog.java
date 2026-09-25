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

/** Журнал отправленных игроку напоминаний (см. NotificationGateService): кому, что и когда ушло, и вернулся ли игрок
 * (открыл бота/мини-апп) в течение 48 часов. По нему строится отчёт «Рассылки» в админке и считается лимит частоты. */
@Getter
@Setter
@Entity
// Индексы (user_id, sent_at) и (sent_at) создаёт SchemaMigrationRunner обычным SQL: @Index(columnList) чувствителен к
// именам колонок при стратегии snake_case, а ошибка там роняет запуск приложения.
@Table(name = "notification_log")
public class NotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 32)
    private String type;

    @Column(nullable = false)
    private int priority;

    @Column(nullable = false)
    private LocalDateTime sentAt = LocalDateTime.now();

    /** Когда игрок впервые проявил активность после сообщения (в пределах 48 ч); null = не вернулся. */
    private LocalDateTime returnedAt;
}
