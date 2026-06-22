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
@Table(name = "app_users")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long telegramId;

    private String telegramUsername;
    private String telegramFirstName;
    private String telegramLastName;
    private String nickname;
    private String staffRole;
    private Integer age;
    private String country;

    @Column(length = 1000)
    private String platformsCsv;

    @Column(length = 1000)
    private String interestsCsv;

    private boolean profileCompleted;
    private boolean registrationCompleted;
    private long xp;
    private long weeklyXp;
    private long coins;
    private long tickets;
    private int completedQuests;
    private int invitedFriends;
    private int streakDays;
    private Long referredByTelegramId;
    private boolean referralRewardProcessed;
    private LocalDate lastActivityDate;
    private LocalDateTime createdAt;

    // Avatar
    private String avatarFileId;

    // Antifaud
    private boolean fraudSuspect;

    // Sink items
    private LocalDateTime excBoostActiveUntil;
    private String profileTitle;
    private boolean retryInsuranceActive;

    // Withdrawal limits
    private long monthlyWithdrawnExc;
    private int withdrawalMonth;
    private int withdrawalYear;
}
