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

/** VPN игрока: один клиент в панели 3x-ui на один Telegram-аккаунт. Срок и лимиты исполняет сама панель
 *  (клиент отключается по истечении expiryTime), здесь - привязка к игроку, признак «пробный уже брали» и ссылка подписки. */
@Getter
@Setter
@Entity
@Table(name = "vpn_accounts")
public class VpnAccount {

    public static final String PLAN_TRIAL = "TRIAL";
    public static final String PLAN_PAID = "PAID";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long telegramId;

    /** Метка клиента в панели: egc_&lt;telegramId&gt;. По ней же ищем клиента при повторных вызовах. */
    @Column(nullable = false, length = 64)
    private String panelEmail;

    @Column(nullable = false, length = 64)
    private String subId;

    @Column(nullable = false, length = 64)
    private String clientUuid;

    @Column(nullable = false, length = 64)
    private String clientAuth;

    @Column(nullable = false, length = 16, columnDefinition = "varchar(16) default 'TRIAL'")
    private String planType = PLAN_TRIAL;

    @Column(nullable = false, length = 16, columnDefinition = "varchar(16) default 'PENDING'")
    private String status = STATUS_PENDING;

    private LocalDateTime expiresAt;

    /** Когда взят пробный период; не null = пробный больше не выдаётся (один раз на аккаунт). */
    private LocalDateTime trialUsedAt;

    /** Ссылка платежа последней оплаты - защита от двойного начисления при повторной доставке события оплаты. */
    @Column(length = 512)
    private String lastPaymentRef;

    /** Какие напоминания о сроке уже отправлены для текущего expiresAt: 0 - никаких, 1 - «осталось 2 дня», 2 - «срок истёк». Сбрасывается при выдаче/продлении. */
    @Column(nullable = false, columnDefinition = "int default 0")
    private int reminderStage = 0;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
