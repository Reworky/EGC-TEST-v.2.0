package ru.gamebot.platform.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** A/B-рассылка «вывод у тебя уже открыт» (2026-10-17): группа A получает сообщение, B - контроль. Строка создаётся при назначении
 *  группы, sentAt заполняется после отправки (повторно не шлём). Id = id игрока. */
@Getter
@Setter
@Entity
@Table(name = "withdrawal_nudges")
public class WithdrawalNudge {

    @Id
    private Long id;

    @Column(name = "grp", nullable = false, length = 1)
    private String grp;

    @Column(name = "assigned_at", nullable = false)
    private LocalDateTime assignedAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;
}
