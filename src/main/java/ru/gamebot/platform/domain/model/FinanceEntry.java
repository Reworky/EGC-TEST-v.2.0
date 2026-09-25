package ru.gamebot.platform.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Ручная запись дохода проекта для финансовой сводки (P&L): прямая продажа рекламы (комиссия менеджера), Yandex РСЯ и т.п.
 *  Данных нет в самой платформе, поэтому вводятся админом вручную. */
@Getter
@Setter
@Entity
@Table(name = "finance_entries")
public class FinanceEntry {

    public static final String DIRECT_ADS = "DIRECT_ADS";
    public static final String YANDEX_RSYA = "YANDEX_RSYA";
    public static final String OTHER = "OTHER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 24)
    private String kind;

    /** Сумма дохода в рублях (уже за вычетом комиссии менеджера, если она удержана - см. примечание). */
    @Column(nullable = false)
    private long amountRub;

    @Column(nullable = false)
    private LocalDate entryDate;

    @Column(length = 255)
    private String note;

    private LocalDateTime createdAt = LocalDateTime.now();

    public static String kindLabel(String kind) {
        return switch (kind == null ? "" : kind) {
            case DIRECT_ADS -> "Прямая реклама";
            case YANDEX_RSYA -> "Yandex РСЯ";
            default -> "Прочее";
        };
    }
}
