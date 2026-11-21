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

/** Конверсия, о которой сообщил постбек LeadGid (20.11.2026): сырые параметры хранятся целиком, чтобы можно было сверить имена макросов с кабинетом.
 *  submissionId - номер заявки на квест из метки SubID 4 (кнопка «Оформить карту» ведёт на витрину с ?sid=номер); пусто, если метка не дошла. */
@Getter
@Setter
@Entity
@Table(name = "leadgid_conversions")
public class LeadgidConversion {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String UNKNOWN = "UNKNOWN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** Заявка на квест из SubID 4 или null. */
    @Column(name = "submission_id")
    private Long submissionId;

    /** Статус как прислал LeadGid. */
    @Column(length = 40)
    private String status;

    /** Нормализованный статус: PENDING, APPROVED, REJECTED, UNKNOWN. */
    @Column(name = "status_norm", length = 16)
    private String statusNorm;

    @Column(name = "offer_id", length = 32)
    private String offerId;

    @Column(length = 32)
    private String payout;

    /** ID конверсии в LeadGid (для защиты от повторов). */
    @Column(name = "external_id", length = 64)
    private String externalId;

    /** Все параметры запроса, кроме token. */
    @Column(length = 2000)
    private String raw;
}
