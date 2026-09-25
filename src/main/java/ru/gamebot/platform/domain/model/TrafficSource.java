package ru.gamebot.platform.domain.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "traffic_sources")
@Getter
@Setter
public class TrafficSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(columnDefinition = "bigint default 0")
    private long clicks;

    /** Сколько потрачено на закуп этого источника, ₽ — вводится админом вручную (кнопка «Указать расход»
     *  в карточке источника). Нужен для «₽ за активного игрока / за игрока со 2-м квестом» в сравнении закупов. */
    @Column(columnDefinition = "bigint default 0")
    private long spendRub;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
