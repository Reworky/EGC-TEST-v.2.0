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

/** Ручная оплата рублями (перевод на карту/СБП по реквизитам из VPN2_CARD_DETAILS): игрок выбирает тариф, переводит, присылает чек, админ подтверждает.
 *  AWAITING_PROOF - реквизиты показаны, ждём чек; PENDING - чек прислан, ждёт админа; APPROVED - ключ выдан; REJECTED - отклонено. */
@Getter
@Setter
@Entity
@Table(name = "vpn2_manual_payments")
public class Vpn2ManualPayment {

    public static final String STATUS_AWAITING_PROOF = "AWAITING_PROOF";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long telegramId;

    @Column(nullable = false, length = 16)
    private String tariff;

    @Column(nullable = false)
    private int amountRub;

    @Column(nullable = false, length = 16, columnDefinition = "varchar(16) default 'AWAITING_PROOF'")
    private String status = STATUS_AWAITING_PROOF;

    /** file_id скриншота/файла чека в Telegram (для просмотра админом). */
    @Column(length = 256)
    private String proofFileId;

    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean proofIsPhoto = true;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime decidedAt;
}
