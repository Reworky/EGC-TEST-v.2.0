package ru.gamebot.platform.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Журнал рассылки про EGC VPN по игрокам: кому, какая волна и вариант текста, дошло ли. Нужен, чтобы не слать повторно и считать воронку по вариантам A/B. */
@Getter
@Setter
@Entity
@Table(name = "vpn_broadcast_log", indexes = @Index(name = "idx_vpn_bc_log_tg", columnList = "telegramId"))
public class VpnBroadcastLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long telegramId;

    /** vpnw1 / vpnw2 */
    @Column(nullable = false, length = 16)
    private String campaign;

    /** A / B для волны 1, «-» для остальных */
    @Column(nullable = false, length = 8)
    private String variant;

    @Column(nullable = false)
    private boolean delivered;

    @Column(length = 200)
    private String error;

    @Column(nullable = false)
    private LocalDateTime sentAt;
}
