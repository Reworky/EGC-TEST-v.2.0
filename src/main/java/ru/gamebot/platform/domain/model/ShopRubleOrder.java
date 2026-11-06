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

/** Заявка на оплату доната «токены + рубли» через менеджера (06.11.2026): рублёвую часть игрок переводит по реквизитам менеджера, после подтверждения списываются EXC и создаётся заявка на выдачу. */
@Getter
@Setter
@Entity
@Table(name = "shop_ruble_orders")
public class ShopRubleOrder {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long telegramId;
    private String gameKey;
    private String packageKey;
    /** EXC-часть, фиксируется в момент заявки (списывается при подтверждении). */
    @Column(columnDefinition = "bigint default 0")
    private long excAmount;
    /** Рублёвая часть, которую игрок переводит менеджеру. */
    @Column(columnDefinition = "bigint default 0")
    private long rubAmount;
    private String status = STATUS_PENDING;
    private LocalDateTime createdAt;
    private LocalDateTime decidedAt;
}
