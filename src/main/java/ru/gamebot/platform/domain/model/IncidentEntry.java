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

/** Ручной трекер открытых багов/инцидентов (вкладка «Техническое здоровье»): известные проблемы, которые могут искажать метрики. */
@Getter
@Setter
@Entity
@Table(name = "incident_entries")
public class IncidentEntry {

    public static final String OPEN = "OPEN";
    public static final String CLOSED = "CLOSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    /** HIGH / MEDIUM / LOW */
    @Column(nullable = false, length = 8)
    private String priority;

    @Column(nullable = false, length = 8)
    private String status = OPEN;

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime closedAt;

    public static String priorityIcon(String priority) {
        return switch (priority == null ? "" : priority) {
            case "HIGH" -> "🔴";
            case "MEDIUM" -> "🟡";
            default -> "🟢";
        };
    }
}
