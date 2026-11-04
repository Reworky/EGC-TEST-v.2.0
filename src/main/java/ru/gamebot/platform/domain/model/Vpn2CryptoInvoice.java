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

/** Счёт в CryptoBot (Crypto Pay API) на оплату тарифа: сумма в рублях, оплата TON/USDT. Оплату находит опрос раз в минуту (webhook не нужен). */
@Getter
@Setter
@Entity
@Table(name = "vpn2_crypto_invoices")
public class Vpn2CryptoInvoice {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_EXPIRED = "EXPIRED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long telegramId;

    @Column(nullable = false, length = 16)
    private String tariff;

    @Column(nullable = false)
    private int amountRub;

    /** invoice_id в CryptoBot. */
    @Column(nullable = false, unique = true)
    private Long cryptoInvoiceId;

    @Column(length = 512)
    private String payUrl;

    @Column(nullable = false, length = 16, columnDefinition = "varchar(16) default 'PENDING'")
    private String status = STATUS_PENDING;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime paidAt;
}
