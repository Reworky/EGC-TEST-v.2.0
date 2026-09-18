package ru.gamebot.platform.domain.model;

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
import ru.gamebot.platform.domain.enums.GemPurchaseStatus;

/** Заявка на покупку внутриигровой валюты за реальные деньги (пилот — вручную, см. GemPurchaseService).
 *  Игрок платит переводом вне бота (карта/СБП), прикладывает скриншот, админ вручную закупает на
 *  топап-сервисе и отмечает заявку выполненной — тогда начисляется XP-бонус. */
@Getter
@Setter
@Entity
@Table(name = "gem_purchase_requests")
public class GemPurchaseRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private AppUser user;

    /** Пока всегда "Brawl Stars" — поле уже готово под другие игры на будущее. */
    private String gameName;

    /** Ключ пакета из GemPurchaseService.PACKAGES (например "80") — источник правды по гемам/цене/XP на момент покупки. */
    private String packageKey;
    private int gems;
    private long priceRub;
    private long xpBonus;

    /** Игровой тег на момент заявки (Brawl Stars tag) — куда админ вручную зачисляет валюту. */
    private String gameTag;

    /** Короткий код в комментарии к переводу, чтобы сверить платёж с заявкой вручную. */
    private String paymentCode;

    /** file_id скриншота подтверждения оплаты. */
    private String paymentProofFileId;

    @Enumerated(EnumType.STRING)
    private GemPurchaseStatus status;

    private String rejectReason;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
