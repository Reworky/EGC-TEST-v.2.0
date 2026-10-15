package ru.gamebot.platform.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/** Журнал «в какие дни игрок был активен» (с 2026-10-15): по одной строке на игрока и день по МСК. Основа когортного возврата D1/D7/D30
 *  (ТЗ «аналитика удержания и источников»): раньше хранилась только последняя дата активности, по ней точный возврат на день N не посчитать.
 *  Активность = любое действие игрока в боте (сообщение, нажатие кнопки) или открытие мини-аппа; рассылки и фоновые сообщения бота не считаются.
 *  История до этой даты не восстанавливается, поэтому когорты старше 2026-10-15 в будущей вкладке «Удержание» будут помечены как неполные. */
@Getter
@Setter
@Entity
@Table(name = "user_activity_days",
        uniqueConstraints = @UniqueConstraint(name = "uq_uad_user_day", columnNames = {"user_id", "day"}),
        indexes = @Index(name = "idx_uad_day", columnList = "day"))
public class UserActivityDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDate day;

    /** bot | app: откуда пришла первая за день активность. */
    @Column(nullable = false, length = 8)
    private String source;
}
