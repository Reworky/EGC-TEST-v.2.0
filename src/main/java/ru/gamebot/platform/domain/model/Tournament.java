package ru.gamebot.platform.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "tournaments")
public class Tournament {

    public enum Status { REGISTRATION, ACTIVE, FINISHED, CANCELLED_LOW_TURNOUT }
    /** QUEST_COUNT - по числу квестов; BRAWL_TROPHIES / CLASH_ROYALE_TROPHIES - «трофи-марафон»: очки = прирост
     *  трофеев по официальному API игры (стартовый и финальный снимки). Значение колонки scoring_type - varchar(32). */
    public enum ScoringType {
        QUEST_COUNT, BRAWL_TROPHIES, CLASH_ROYALE_TROPHIES;

        /** Турнир-марафон по трофеям (любая игра с API): регистрация только через бота по игровому тегу. */
        public boolean isTrophyRace() {
            return this == BRAWL_TROPHIES || this == CLASH_ROYALE_TROPHIES;
        }

        /** Тип подсчёта по названию игры, введённому админом при создании (без учёта регистра). */
        public static ScoringType forGame(String gameName) {
            if (gameName == null) return QUEST_COUNT;
            String g = gameName.trim();
            if ("Brawl Stars".equalsIgnoreCase(g)) return BRAWL_TROPHIES;
            if ("Clash Royale".equalsIgnoreCase(g)) return CLASH_ROYALE_TROPHIES;
            return QUEST_COUNT;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    /** Свободный текст для карточки турнира (правила/контекст сверх стандартных полей) — необязательное
     * поле, редактируется админом отдельно от остальных (см. GamePlatformBot admin:tournaments:edit-*). */
    @Column(length = 2000)
    private String description;

    private String gameName;

    @Column(nullable = false)
    private long entryFeeExc;

    @Column(columnDefinition = "bigint default 0")
    private long prizePoolExc;

    private LocalDateTime startDate;
    private LocalDateTime endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(32) default 'QUEST_COUNT'")
    private ScoringType scoringType = ScoringType.QUEST_COUNT;

    private LocalDateTime createdAt;

    private String photoFileId;

    // null = no minimum enforced, turnir always proceeds to ACTIVE regardless of entry count
    private Integer minParticipants;

    /** Черновик поста с итогами для канала, ждущий одобрения админа (null = ничего не ждёт). Хранится в БД,
     * а не в памяти бота: иначе перезапуск на деплое терял карточку, а два завершившихся турнира подряд
     * затирали друг друга. Очищается после публикации/отклонения. */
    @Column(length = 4096) // лимит длины сообщения Telegram
    private String resultsFeedText;

    /** Админу при создании было показано предупреждение о границе сезона Clash Royale (сброс трофеев около 1-го числа
     *  месяца) и он подтвердил создание осознанно (ТЗ «Турнир Clash Royale», п. 4.2). Только отметка, на подсчёт не влияет. */
    @Column(columnDefinition = "boolean default false")
    private boolean seasonBoundaryWarningShown;

    /** Когда откроется регистрация; null = сразу после создания (как было раньше). До этого момента турнир скрыт от игроков
     *  (нет ни в боте, ни в мини-аппе) и записаться нельзя, а в момент открытия админу уходит пост на одобрение. */
    private LocalDateTime registrationOpenDate;

    /** Пост «регистрация открыта» уже подготовлен (идемпотентность планировщика). */
    @Column(columnDefinition = "boolean default false")
    private boolean registrationAnnounced;

    /** Пост «турнир стартовал» уже подготовлен (идемпотентность планировщика). */
    @Column(columnDefinition = "boolean default false")
    private boolean startAnnounced;

    /** Какой пост сейчас ждёт одобрения в resultsFeedText: REGISTRATION / START / RESULTS; null = итоги (как раньше). */
    @Column(length = 16)
    private String feedStage;

    /** Регистрация уже открыта (или открывалась сразу): у турнира без даты открытия всегда true. */
    public boolean isRegistrationOpen() {
        return registrationOpenDate == null || !LocalDateTime.now().isBefore(registrationOpenDate);
    }

    /** Пустое значение у старых записей трактуем как QUEST_COUNT (колонка появилась позже создания части турниров) -
     *  иначе `getScoringType().isTrophyRace()` упал бы NPE. Явный геттер заменяет сгенерированный Lombok'ом. */
    public ScoringType getScoringType() {
        return scoringType != null ? scoringType : ScoringType.QUEST_COUNT;
    }
}
