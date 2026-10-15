package ru.gamebot.platform.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Расход на закуп источника трафика ЗА ПЕРИОД (кнопка «🗓 Расход за период» в карточке источника, 2026-10-15). Нужен вкладке «Удержание»:
 *  стоимость вернувшегося считается как расход / вернувшиеся из игроков ЭТОГО источника, зарегистрированных в этот период. Общая сумма
 *  TrafficSource.spendRub при добавлении записи увеличивается на amountRub (чтобы «Источники трафика» и P&L оставались согласованными). */
@Getter
@Setter
@Entity
@Table(name = "traffic_spend_entries", indexes = @Index(name = "idx_tse_code", columnList = "source_code"))
public class TrafficSpendEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_code", nullable = false, length = 64)
    private String sourceCode;

    @Column(name = "amount_rub", nullable = false)
    private long amountRub;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
