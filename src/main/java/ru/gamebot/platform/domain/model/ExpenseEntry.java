package ru.gamebot.platform.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** Ручная запись расхода проекта для финансовой сводки (P&L): закуп трафика, серверы, команда и т.п.
 *  Выплаты игрокам сюда не входят: они берутся из заявок на вывод. */
@Getter
@Setter
@Entity
@Table(name = "expense_entries")
public class ExpenseEntry {

    public static final String TRAFFIC = "TRAFFIC";
    public static final String REWARDS = "REWARDS";
    public static final String INFRA = "INFRA";
    public static final String TEAM = "TEAM";
    public static final String TAX = "TAX";
    public static final String OTHER = "OTHER";

    /** Порядок статей в меню и в отчёте. */
    public static final List<String> KINDS = List.of(TRAFFIC, REWARDS, INFRA, TEAM, TAX, OTHER);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 24)
    private String kind;

    @Column(nullable = false)
    private long amountRub;

    @Column(nullable = false)
    private LocalDate entryDate;

    @Column(length = 255)
    private String note;

    private LocalDateTime createdAt = LocalDateTime.now();

    public static String kindLabel(String kind) {
        return switch (kind == null ? "" : kind) {
            case TRAFFIC -> "Закуп трафика и реклама";
            case REWARDS -> "Закупка наград";
            case INFRA -> "Серверы и сервисы";
            case TEAM -> "Команда и подрядчики";
            case TAX -> "Налоги и комиссии";
            default -> "Прочие расходы";
        };
    }
}
