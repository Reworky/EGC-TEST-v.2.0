package ru.gamebot.platform.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Строка прайс-листа на рекламные размещения (редактируется прямо в админке). */
@Getter
@Setter
@Entity
@Table(name = "ad_price_items")
public class AdPriceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Тип размещения: пост в канале / квест-интеграция / упоминание в боте и т.п. */
    @Column(nullable = false, length = 120)
    private String title;

    private long priceRub;

    @Column(length = 255)
    private String conditions;
}
