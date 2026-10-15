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

/** Покупка за Telegram Stars (XTR) — журнал для учёта и сверки с реальными поступлениями от Telegram.
 *  Курс Stars->₽ Telegram не публикует как фиксированное число, поэтому пополнение payout pool от
 *  этой выручки делается администратором вручную (тот же admin-флоу, что уже пополняет фонд от
 *  спонсоров) по факту реальной выплаты от Telegram, а не автоматическим пересчётом здесь. */
@Getter
@Setter
@Entity
@Table(name = "stars_purchases")
public class StarsPurchase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long telegramId;

    /** Payload инвойса — что именно куплено, напр. "starsitem:AVATAR_FRAME". */
    @Column(nullable = false, length = 64)
    private String itemType;

    @Column(nullable = false)
    private int starsAmount;

    /** ID платежа Telegram бывает длиннее 128 символов (2026-10-15: 131 - запись падала, и покупка не выдавалась); запас до 512. */
    @Column(length = 512)
    private String telegramPaymentChargeId;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
