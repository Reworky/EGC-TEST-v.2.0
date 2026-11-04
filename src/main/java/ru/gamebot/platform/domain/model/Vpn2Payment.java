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

/** Оплата в боте EGC VPN (Telegram Stars): журнал для статистики и сверки. Пишется после успешной выдачи ключа. */
@Getter
@Setter
@Entity
@Table(name = "vpn2_payments")
public class Vpn2Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long telegramId;

    /** Ключ тарифа: M1, M3, M12. */
    @Column(nullable = false, length = 16)
    private String tariff;

    @Column(nullable = false)
    private int starsAmount;

    /** Сколько EXC списано за скидку (0 - без скидки). */
    @Column(nullable = false, columnDefinition = "bigint default 0")
    private long excSpent = 0;

    /** ID платежа Telegram бывает длиннее 128 символов (см. StarsPurchase); запас до 512. */
    @Column(length = 512)
    private String telegramPaymentChargeId;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
