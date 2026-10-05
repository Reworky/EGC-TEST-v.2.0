package ru.gamebot.platform.domain.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** Статистика рекламы AdsGram в боте по дням: сколько раз запросили объявление, сколько нашлось и сколько раз AdsGram ничего не вернул (no-fill). */
@Getter
@Setter
@Entity
@Table(name = "bot_ad_stats")
public class BotAdStat {

    @Id
    private LocalDate day;

    private long requests;
    private long filled;
    private long empty;
}
