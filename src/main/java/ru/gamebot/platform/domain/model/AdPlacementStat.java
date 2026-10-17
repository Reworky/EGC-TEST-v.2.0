package ru.gamebot.platform.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** Статистика рекламы за награду по местам показа и дням: сколько раз игрок нажал «смотреть» (requests) и сколько наград/зачётов выдано (rewards).
 *  Ключ id = «yyyy-MM-dd|место» (quests, wallet, wheel, streak, bot, ...). */
@Getter
@Setter
@Entity
@Table(name = "ad_placement_stats")
public class AdPlacementStat {

    @Id
    private String id;

    /** Колонка stat_day, а не day: DAY - ключевое слово H2/HQL, таблица с такой колонкой не создавалась (инцидент 2026-10-17: «Syntax error ... aps1_0.day»). */
    @Column(name = "stat_day")
    private LocalDate day;
    private String placement;
    private long requests;
    private long rewards;
}
