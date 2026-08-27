package ru.gamebot.platform.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import ru.gamebot.platform.domain.enums.RejectionReasonCode;
import ru.gamebot.platform.domain.enums.SubmissionStatus;

@Getter
@Setter
@Entity
@Table(name = "quest_submissions")
public class QuestSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(columnDefinition = "bigint default 0")
    private Long displayId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quest_id")
    private Quest quest;

    @Enumerated(EnumType.STRING)
    private SubmissionStatus status;

    private String mediaType;

    @Column(length = 1000)
    private String mediaFileId;

    // Additional media files (pipe-separated fileIds for multi-screenshot support)
    @Column(length = 4000, columnDefinition = "varchar(4000) default ''")
    private String extraMediaFileIds;

    // Anti-fraud: pipe-separated file_unique_id values for all submitted photos
    @Column(length = 4000, columnDefinition = "varchar(4000) default ''")
    private String photoUniqueIds;

    @Column(columnDefinition = "boolean default false")
    private boolean duplicatePhotoDetected;

    @Column(length = 1000)
    private String externalLink;

    @Column(length = 2000)
    private String userComment;

    @Column(length = 2000)
    private String moderatorComment;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime expiresAt;

    /** Отдельный порядковый номер, присваивается только при одобрении квеста (не связан с displayId заявки). */
    private Long completionDisplayId;

    /** Рублёвый эквивалент награды, зафиксированный в момент одобрения (HR × EXC / 100). null = старые записи. */
    @Column(nullable = true)
    private Long fixedRubValue;

    /** Флаг: уведомление «2 часа до дедлайна» уже отправлено. */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean deadlineWarningSent;

    /** AI verdict: APPROVE, REJECT, MANUAL (null = not checked yet) */
    @Column(length = 10)
    private String aiDecision;

    /** AI confidence score 0.0–1.0 */
    @Column
    private Double aiConfidence;

    /** Human-readable reason returned by AI */
    @Column(length = 2000)
    private String aiReason;

    /** JSON-encoded checks map from AI response */
    @Column(length = 4000)
    private String aiChecks;

    /** Timestamp when AI (or moderator) reviewed the submission */
    private LocalDateTime aiReviewedAt;

    /** Who reviewed: "AI" or moderator nickname */
    @Column(length = 100)
    private String reviewedBy;

    /** Причина отклонения (для быстрых кнопок отклонения); null у AI-отклонений и старых записей. */
    @Enumerated(EnumType.STRING)
    private RejectionReasonCode rejectionReasonCode;

    /** Telegram ID модератора, который принял решение об отклонении (для аналитики, если модераторов несколько). */
    private Long moderatorTelegramId;
}
