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
import ru.gamebot.platform.domain.enums.ClashRoyaleVerifyType;
import ru.gamebot.platform.domain.enums.ClashVerifyType;

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

    /** null = обычный ручной квест со скриншотом — единственный флаг "включена авто-верификация через Brawl Stars API".
     *  columnDefinition принудительно VARCHAR — иначе H2 создаёт нативный ENUM с фиксированным списком значений
     *  на момент первого деплоя, и расширение Java-enum'а потом падает с "Value not permitted" (инцидент 2026-08-31,
     *  см. clashVerifyType ниже — там баг реально выстрелил). */
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(20)")
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

    /** null = обычный квест (или уже авто-верифицируемый через brawlVerifyType) — включает авто-верификацию через официальный Clash of Clans API.
     *  columnDefinition = varchar принудительно — см. оговорку у brawlVerifyType (инцидент 2026-08-31: H2
     *  создал нативный ENUM('ATTACK_WINS','RESOURCES','TOWN_HALL') на первом деплое, расширение enum'а
     *  до 9 значений уронило бота на старте, чинили ALTER COLUMN ... VARCHAR(20) вручную через H2 Shell). */
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(20)")
    private ClashVerifyType clashVerifyType;

    /** Целевая дельта с момента взятия квеста: побед в атаках (ATTACK_WINS), золота/эликсира (RESOURCES, берётся максимум из двух) или уровней Ратуши (TOWN_HALL). */
    private Integer clashTargetCount;

    /** null = обычный квест — включает авто-верификацию через официальный Clash Royale API. varchar принудительно (см. clashVerifyType). */
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(20)")
    private ClashRoyaleVerifyType clashRoyaleVerifyType;

    /** Целевая дельта с момента взятия квеста — для всех типов Clash Royale верификации. */
    private Integer clashRoyaleTargetCount;

    /** Краткое условие (до ~150 символов) для подстановки в шаблон быстрого отклонения «Недостаточно данных». */
    @Column(length = 200)
    private String shortCondition;
}
