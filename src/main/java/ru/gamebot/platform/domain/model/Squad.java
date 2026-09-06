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

@Getter
@Setter
@Entity
@Table(name = "squads")
public class Squad {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false)
    private Long captainTelegramId;

    @Column(unique = true, length = 20)
    private String inviteCode;

    @Column(length = 20)
    private String status; // ACTIVE / DISBANDED

    private LocalDateTime createdAt;

    /** Бонусные очки к недельному рейтингу отряда (см. SquadService.squadWeeklyXp) — начисляются, когда
     *  реферал вступает в отряд ПРИГЛАСИВШЕГО в течение 7 дней после своей регистрации (см.
     *  SquadService.awardReferralSquadBonus). Сбрасывается вместе с остальным недельным рейтингом
     *  в WeeklyResetScheduler, чтобы не накапливаться бессрочно. */
    @Column(columnDefinition = "bigint default 0")
    private long weeklyBonusPoints;
}
