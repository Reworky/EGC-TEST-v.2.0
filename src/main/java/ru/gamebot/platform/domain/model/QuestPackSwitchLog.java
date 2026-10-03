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

/** Журнал включений пачек квестов: нужен, чтобы в аналитике видеть, когда и где менялся набор квестов. */
@Getter
@Setter
@Entity
@Table(name = "quest_pack_switch_log")
public class QuestPackSwitchLog {

    public static final String MANUAL = "MANUAL";
    public static final String AUTO = "AUTO";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String gameName;

    @Column(nullable = false)
    private String packName;

    private LocalDateTime switchedAt;

    /** MANUAL — админ нажал кнопку, AUTO — сработало расписание. */
    private String source;

    private int activated;
    private int hidden;
}
