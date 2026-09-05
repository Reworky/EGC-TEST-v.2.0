package ru.gamebot.platform.api.controller;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
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
import ru.gamebot.platform.service.UserService;

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
    private final UserService userService;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${app.actionpay-postback-token:}")
    private String actionpayToken;

    @Value("${app.admitad-postback-token:}")
    private String admitadToken;

    @Value("${app.adsgram-reward-token:}")
    private String adsgramRewardToken;

    @Value("${app.telega-reward-token:}")
    private String telegaRewardToken;

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

    /**
     * Reward URL для рекламных постов AdsGram в самом боте (не мини-апп). В отличие от actionpay/admitad
     * это НЕ сервер-сервер вызов — сюда переходит реальный браузер игрока после клика по "Забрать награду",
     * поэтому ответ — читаемый HTML, а не голый текст. AdsGram не документирует точное имя параметра
     * с ID игрока в финальном редиректе — логируем все параметры целиком, чтобы свериться с реальностью
     * при первом тестовом проходе (см. память проекта / план фичи), и пробуем несколько вероятных имён.
     * Награда начисляется только если у игрока есть непросроченный "ожидающий показ"
     * ({@link UserService#markAdRequested}) — сам по себе токен в URL не секрет для конкретного игрока
     * (Telegram ID не является тайной), это вторая линия защиты от прямого вызова эндпоинта.
     */
    @GetMapping(value = "/adsgram", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> adsgramReward(@RequestParam Map<String, String> params) {
        if (!isValidToken(params.get("token"), adsgramRewardToken)) {
            log.warn("[AdsgramReward] Postback rejected: bad token");
            return ResponseEntity.status(403).body(rewardPage(false));
        }
        log.info("[AdsgramReward] incoming params: {}", params);

        Long telegramId = parseFirstLong(params, "tgid", "userid", "user_id", "uid");
        boolean granted = telegramId != null && userService.claimPendingAdReward(telegramId, UserService.AdRewardSource.ADSGRAM);
        if (!granted) {
            log.warn("[AdsgramReward] Reward not granted (telegramId={}, params={})", telegramId, params);
        }
        return ResponseEntity.ok(rewardPage(granted));
    }

    /** Тот же паттерн, что и adsgramReward — Telega.io зовёт GET на этот URL при наступлении REWARD-события
     * в мини-аппе, [userid] в примере из их формы (docs всплывающая подсказка "What is a Reward URL?")
     * заменяется реальным Telegram ID. Реиспользует тот же UserService.claimPendingAdReward/markAdRequested,
     * что и бот-реклама AdsGram — источник рекламы (какая именно сеть) для лимита/защиты не важен. */
    @GetMapping(value = "/telega", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> telegaReward(@RequestParam Map<String, String> params) {
        if (!isValidToken(params.get("token"), telegaRewardToken)) {
            log.warn("[TelegaReward] Postback rejected: bad token");
            return ResponseEntity.status(403).body(rewardPage(false));
        }
        log.info("[TelegaReward] incoming params: {}", params);

        Long telegramId = parseFirstLong(params, "userid", "user_id", "tgid", "uid");
        boolean granted = telegramId != null && userService.claimPendingAdReward(telegramId, UserService.AdRewardSource.TELEGA);
        if (!granted) {
            log.warn("[TelegaReward] Reward not granted (telegramId={}, params={})", telegramId, params);
        }
        return ResponseEntity.ok(rewardPage(granted));
    }

    private Long parseFirstLong(Map<String, String> params, String... keys) {
        for (String key : keys) {
            String raw = params.get(key);
            if (raw != null && !raw.isBlank()) {
                try {
                    return Long.parseLong(raw.trim());
                } catch (NumberFormatException ignored) {
                    // пробуем следующее имя параметра
                }
            }
        }
        return null;
    }

    private String rewardPage(boolean granted) {
        String message = granted
                ? "✅ Награда начислена!"
                : "Не удалось начислить награду — попробуйте посмотреть рекламу заново.";
        return "<html><body style=\"font-family:sans-serif;text-align:center;padding:40px\">"
                + message + "<br><br><a href=\"https://t.me/invitetogamebot\">Вернуться в бота</a>"
                + "</body></html>";
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
