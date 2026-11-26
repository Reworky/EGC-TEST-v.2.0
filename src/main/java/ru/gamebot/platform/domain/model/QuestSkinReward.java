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

/** Скин-награда за партнёрский квест (26.11.2026, ПСБ): создаётся ТОЛЬКО по подтверждённому постбеку партнёра, одна на игрока за квест.
 *  Статусы: WAIT_LINK (ждём ссылку обмена Steam) → BUYING (покупка на market.csgo.com идёт) → DONE; MANUAL - дальше ведёт менеджер вручную. */
@Getter
@Setter
@Entity
@Table(name = "quest_skin_rewards")
public class QuestSkinReward {

    public static final String WAIT_LINK = "WAIT_LINK";
    public static final String BUYING = "BUYING";
    public static final String DONE = "DONE";
    public static final String MANUAL = "MANUAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Ключ миссии + ID игрока: уникален, вторая награда тому же игроку не создаётся. */
    @Column(name = "reward_key", length = 64, nullable = false, unique = true)
    private String rewardKey;

    @Column(name = "submission_id")
    private Long submissionId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "telegram_id")
    private Long telegramId;

    @Column(length = 16, nullable = false)
    private String status;

    @Column(name = "trade_url", length = 300)
    private String tradeUrl;

    /** Номер попытки покупки: входит в custom_id маркетплейса. */
    @Column(columnDefinition = "integer default 0")
    private int attempt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
