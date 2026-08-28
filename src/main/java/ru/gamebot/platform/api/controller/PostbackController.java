package ru.gamebot.platform.api.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.gamebot.platform.domain.enums.SubmissionStatus;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.model.QuestSubmission;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.QuestRepository;
import ru.gamebot.platform.event.ExternalQuestApprovedEvent;
import ru.gamebot.platform.service.QuestService;

/**
 * Приём постбеков от партнёрских CPA-сетей для внешних квестов с {@link Quest#isExternalAutoApprove()}.
 * Одобрение идёт через {@link QuestService#approveExternalConversion} — тот же путь начисления награды,
 * что и у обычной модерации, только без ручной проверки скриншота.
 *
 * Каждая сеть — отдельный эндпоинт (свои имена параметров/макросов), но вся общая логика
 * (поиск игрока, поиск квеста по сети+ID оффера, идемпотентность, проверка минимальной суммы
 * покупки для PURCHASE-квестов) — в одном месте, {@link #processConversion}. Добавление новой
 * сети — это новый метод-обёртка с маппингом её параметров, без дублирования логики одобрения.
 */
@Slf4j
@RestController
@RequestMapping("/api/postback")
@RequiredArgsConstructor
public class PostbackController {

    private final AppUserRepository appUserRepository;
    private final QuestRepository questRepository;
    private final QuestService questService;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${app.actionpay-postback-token:}")
    private String actionpayToken;

    @Value("${app.admitad-postback-token:}")
    private String admitadToken;

    @GetMapping("/actionpay")
    public ResponseEntity<String> actionPay(
            @RequestParam(required = false) String token,
            @RequestParam(required = false) String subaccount,
            @RequestParam(required = false) String event,
            @RequestParam(required = false) String uniqueid,
            @RequestParam(required = false) String offer,
            @RequestParam(required = false) String payment) {

        if (!isValidToken(token, actionpayToken)) {
            log.warn("[ActionPay] Postback rejected: bad token");
            return ResponseEntity.status(403).body("forbidden");
        }
        if (!"accepted".equalsIgnoreCase(event)) {
            log.info("[ActionPay] Postback ignored, event={}, subaccount={}", event, subaccount);
            return ResponseEntity.ok("ignored");
        }

        return processConversion("actionpay", subaccount, offer, uniqueid, payment);
    }

    /**
     * Параметры сверены с личным кабинетом Admitad («Инструменты» → «Код оптимизации», макросы
     * в квадратных скобках [[...]]): subid — наш идентификатор, offer_id — ID программы, order_id —
     * номер заказа (уникальный ID конверсии, для логов), order_sum — сумма заказа покупателя
     * (используется для порога PURCHASE-квестов, НЕ путать с payment_sum — это наша комиссия),
     * payment_status — new/approved/declined/pending.
     */
    @GetMapping("/admitad")
    public ResponseEntity<String> admitad(
            @RequestParam(required = false) String token,
            @RequestParam(required = false) String subid,
            @RequestParam(value = "payment_status", required = false) String paymentStatus,
            @RequestParam(value = "order_id", required = false) String orderId,
            @RequestParam(value = "offer_id", required = false) String offerId,
            @RequestParam(value = "order_sum", required = false) String orderSum) {

        if (!isValidToken(token, admitadToken)) {
            log.warn("[Admitad] Postback rejected: bad token");
            return ResponseEntity.status(403).body("forbidden");
        }
        if (!"approved".equalsIgnoreCase(paymentStatus)) {
            log.info("[Admitad] Postback ignored, payment_status={}, subid={}", paymentStatus, subid);
            return ResponseEntity.ok("ignored");
        }

        return processConversion("admitad", subid, offerId, orderId, orderSum);
    }

    private boolean isValidToken(String provided, String expected) {
        return expected != null && !expected.isBlank() && expected.equals(provided);
    }

    private ResponseEntity<String> processConversion(String network, String subaccount, String offer,
                                                       String externalId, String paymentRaw) {
        if (subaccount == null || subaccount.isBlank()) {
            return ResponseEntity.badRequest().body("missing subaccount");
        }
        long telegramId;
        try {
            telegramId = Long.parseLong(subaccount.trim());
        } catch (NumberFormatException e) {
            log.warn("[{}] Postback rejected: subaccount is not a telegram id: {}", network, subaccount);
            return ResponseEntity.badRequest().body("bad subaccount");
        }

        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            log.warn("[{}] Postback: user not found for telegramId={}", network, telegramId);
            return ResponseEntity.ok("user not found");
        }

        if (offer == null || offer.isBlank()) {
            log.warn("[{}] Postback rejected: missing offer id, subaccount={}", network, subaccount);
            return ResponseEntity.badRequest().body("missing offer");
        }
        Quest quest = questRepository
                .findFirstByExternalAutoApproveTrueAndActiveTrueAndExternalNetworkAndExternalOfferId(network, offer.trim())
                .orElse(null);
        if (quest == null) {
            log.error("[{}] Postback received for unknown/inactive offer id={}, subaccount={}", network, offer, subaccount);
            return ResponseEntity.ok("no active quest for this offer");
        }

        Long paymentRub = parsePaymentRub(paymentRaw);

        QuestSubmission existing = questService.getLatestSubmission(user, quest);
        boolean alreadyApproved = existing != null && existing.getStatus() == SubmissionStatus.APPROVED;

        QuestSubmission submission = questService.approveExternalConversion(user, quest, paymentRub);
        if (submission == null) {
            log.info("[{}] Postback below minimum payment threshold, telegramId={}, offer={}, payment={}",
                    network, telegramId, offer, paymentRaw);
            return ResponseEntity.ok("below minimum payment");
        }

        log.info("[{}] Postback processed for telegramId={}, externalId={}, submissionId={}, alreadyApproved={}",
                network, telegramId, externalId, submission.getId(), alreadyApproved);

        if (!alreadyApproved) {
            eventPublisher.publishEvent(new ExternalQuestApprovedEvent(this, submission.getId()));
        }

        return ResponseEntity.ok("ok");
    }

    private Long parsePaymentRub(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Math.round(Double.parseDouble(raw.trim().replace(",", ".")));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
