package ru.gamebot.platform.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import ru.gamebot.platform.domain.enums.BrawlVerifyType;

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

    /** Квест проверяется по ТЕКУЩЕМУ состоянию аккаунта (баланс, ранг, лига), а не по свежему действию —
     *  без этого флага такой квест фармится повторно каждый кулдаун без усилий. Блокирует повторное
     *  взятие после APPROVED тем же путём, что и externalAutoApprove (см. QuestService.takeQuestChecked). */
    @Column(columnDefinition = "boolean default false")
    private boolean oneTimePerAccount;

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

    /** null = обычный ручной квест со скриншотом — единственный флаг "включена авто-верификация через Brawl Stars API". */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private BrawlVerifyType brawlVerifyType;

    /** Дельта трофеев (TROPHIES) или число засчитанных боёв (BATTLES). */
    private Integer brawlTargetCount;

    @Column(columnDefinition = "boolean default false")
    private boolean brawlRequireVictory;

    @Column(columnDefinition = "boolean default false")
    private boolean brawlRequireRanked;

    /** Бой должен быть в командном режиме (не соло/дуо Showdown). */
    @Column(columnDefinition = "boolean default false")
    private boolean brawlRequireTeam;

    /** CSV допустимых ключей режима API (например "gemGrab,brawlBall"), OR-логика. null = любой режим. */
    @Column(length = 500)
    private String brawlModeKeys;

    /** CSV допустимых имён бойцов API — засчитывается боец ИГРОКА в этом бою, OR-логика. null = любой боец. */
    @Column(length = 500)
    private String brawlBrawlerNames;

    /** Краткое условие (до ~150 символов) для подстановки в шаблон быстрого отклонения «Недостаточно данных». */
    @Column(length = 200)
    private String shortCondition;
}
