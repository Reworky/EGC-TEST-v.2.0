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
@Table(name = "quests")
public class Quest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, length = 4000)
    private String description;

    private String gameName;
    private String category;
    private String platform;
    private String durationText;

    @Column(columnDefinition = "integer default 0")
    private int durationDays;
    private Integer participantLimit;

    @Column(length = 2000)
    private String requirements;

    @Column(length = 4000)
    private String instruction;

    private long rewardXp;
    private long rewardCoins;
    @jakarta.persistence.Column(columnDefinition = "INT DEFAULT 0")
    private int ticketReward;
    private boolean active;

    @Column(columnDefinition = "boolean default false")
    private boolean councilOnly;

    @Column(columnDefinition = "boolean default false")
    private boolean seasonOnly;

    @Column(columnDefinition = "boolean default false")
    private boolean sponsored;

    private Long sponsorId;

    @Column(columnDefinition = "boolean default false")
    private boolean externalAutoApprove;

    /** ID оффера в партнёрской сети (макрос {offer} в постбеке) — по нему постбек находит нужный квест среди нескольких активных. */
    private String externalOfferId;

    /** Партнёрская сеть, из которой приходит постбек: "actionpay", "admitad" и т.д. Вместе с externalOfferId однозначно определяет квест. */
    private String externalNetwork;

    /** "REGISTRATION" — засчитывается любое подтверждение; "PURCHASE" — дополнительно проверяется externalMinPaymentRub. */
    private String externalTargetType;

    /** Минимальная сумма покупки в рублях для externalTargetType=PURCHASE — постбеки с меньшей суммой игнорируются. */
    private Long externalMinPaymentRub;

    private String photoFileId;
    private LocalDateTime createdAt;

    /** Краткое условие (до ~150 символов) для подстановки в шаблон быстрого отклонения «Недостаточно данных». */
    @Column(length = 200)
    private String shortCondition;
}
