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

/** Регулярный расход (сервер, подписки): раз в месяц в указанный день сам записывается в расходы. */
@Getter
@Setter
@Entity
@Table(name = "recurring_expenses")
public class RecurringExpense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 24)
    private String kind;

    @Column(nullable = false)
    private long amountRub;

    /** День месяца 1-28 (в коротких месяцах и так не больше последнего дня). */
    @Column(nullable = false)
    private int dayOfMonth;

    @Column(length = 255)
    private String note;

    /** Месяц последней записи в формате yyyy-MM: защита от повторной записи в тот же месяц. */
    @Column(length = 7)
    private String lastPostedMonth;

    private LocalDateTime createdAt = LocalDateTime.now();
}
