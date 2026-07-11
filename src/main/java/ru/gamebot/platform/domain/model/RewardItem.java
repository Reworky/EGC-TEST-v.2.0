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

@Getter
@Setter
@Entity
@Table(name = "reward_items")
public class RewardItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(length = 2000)
    private String description;

    private String photoFileId;
    private String category;
    private long priceCoins;
    private boolean active;
    private LocalDateTime createdAt;

    /** Если не null — перед покупкой бот спросит у пользователя эти данные (хранятся в payoutDetails заявки) */
    @Column(length = 512)
    private String userDataPrompt;

    /** Минимальный XP для доступа к товару (Слой 1) */
    @Column(columnDefinition = "integer default 0")
    private int minLevelXp;

    /** Группа лимита: gift_card, pubg_pc, pubg_mobile и т.д. (Слой 2) */
    @Column(length = 64)
    private String purchaseGroup;

    /** Товар скоро появится: отображается в магазине, но купить нельзя */
    @Column(columnDefinition = "boolean default false")
    private boolean comingSoon;

    /**
     * Если задано — этот товар является цифровой косметикой «рамка аватара» (hex-цвет).
     * Применяется пользователю мгновенно при покупке, без одобрения администратора.
     */
    private String avatarFrameColor;
}
