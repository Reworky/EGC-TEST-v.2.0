package ru.gamebot.platform.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "platform_snapshots")
public class PlatformSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private LocalDate snapshotDate;

    @Column(columnDefinition = "bigint default 0")
    private long totalUsers;

    @Column(columnDefinition = "bigint default 0")
    private long newUsersWeek;

    @Column(columnDefinition = "bigint default 0")
    private long active7Days;

    @Column(columnDefinition = "bigint default 0")
    private long active30Days;

    @Column(columnDefinition = "bigint default 0")
    private long totalApprovedQuests;

    @Column(columnDefinition = "bigint default 0")
    private long approvedQuestsMonth;

    @Column(columnDefinition = "bigint default 0")
    private long totalPaidOutExc;

    @Column(columnDefinition = "bigint default 0")
    private long uniqueWithdrawalRecipients;

    @Column(columnDefinition = "bigint default 0")
    private long totalCoinsOnAccounts;

    @Column(columnDefinition = "bigint default 0")
    private long totalTickets;

    @Column(columnDefinition = "bigint default 0")
    private long activeQuestsCount;

    // Воронка вовлечённости — для сравнения "лучше/хуже" по дням (см. кнопка "📸 Снепшот сейчас")
    @Column(columnDefinition = "bigint default 0")
    private long retention7Cohort;

    @Column(columnDefinition = "bigint default 0")
    private long retention7Pct;

    @Column(columnDefinition = "bigint default 0")
    private long retention30Cohort;

    @Column(columnDefinition = "bigint default 0")
    private long retention30Pct;

    @Column(columnDefinition = "bigint default 0")
    private long completionRatePct;

    // Расширение под раздел «Аналитика» (2026-09-25): nullable - у старых снимков значений нет («—», без нулей в трендах).
    private Long dau;
    private Long mau;
    private Long questTakers7d;
    private Long secondQuestReturnPct;
    private Long pendingSubmissions;
    private Long avgReviewMin7d;
    private Long earnedExc7d;
    private Long paidOutExc7d;
    private Long referralsTotal;
    private Long referralsActivated;
    private Long blockedUsers;
    /** Аптайм за 7 дней, промилле (999 = 99,9%). */
    private Long uptimePermille7d;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
