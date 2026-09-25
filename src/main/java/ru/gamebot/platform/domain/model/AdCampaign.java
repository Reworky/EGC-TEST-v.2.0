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

/** Рекламная кампания для истории размещений и медиа-кита. Результат считается по метке источника (TrafficSource.code):
 *  пришло / дошло до 1-го квеста берётся из воронки, CTR = клики по ссылке / просмотры поста (просмотры вводятся вручную). */
@Getter
@Setter
@Entity
@Table(name = "ad_campaigns")
public class AdCampaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Рекламодатель или площадка-партнёр. */
    @Column(nullable = false, length = 120)
    private String advertiser;

    private LocalDate startDate;

    private LocalDate endDate;

    /** Код источника из «📈 Трафик» (t.me/бот?start=src_КОД) - по нему считается результат; может быть пуст. */
    @Column(length = 32)
    private String sourceCode;

    /** Бюджет / сумма сделки, ₽ (включая комиссию менеджера, если применимо). */
    private long budgetRub;

    /** Просмотры рекламного поста (вручную) - знаменатель CTR. */
    private Long impressions;

    @Column(length = 255)
    private String note;

    private LocalDateTime createdAt = LocalDateTime.now();
}
