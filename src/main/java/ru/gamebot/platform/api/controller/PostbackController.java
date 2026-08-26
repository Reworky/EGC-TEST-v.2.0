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
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.model.QuestSubmission;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.QuestRepository;
import ru.gamebot.platform.event.ExternalQuestApprovedEvent;
import ru.gamebot.platform.service.QuestService;

/**
 * Приём постбеков от партнёрских CPA-сетей (сейчас — actionpay) для внешних квестов
 * с {@link Quest#isExternalAutoApprove()}. Одобрение идёт через {@link QuestService#approveExternalConversion}
 * — тот же путь начисления награды, что и у обычной модерации, только без ручной проверки скриншота.
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
    private String expectedToken;

    @GetMapping("/actionpay")
    public ResponseEntity<String> actionPay(
            @RequestParam(required = false) String token,
            @RequestParam(required = false) String subaccount,
            @RequestParam(required = false) String event,
            @RequestParam(required = false) String uniqueid) {

        if (expectedToken == null || expectedToken.isBlank() || !expectedToken.equals(token)) {
            log.warn("[ActionPay] Postback rejected: bad token");
            return ResponseEntity.status(403).body("forbidden");
        }

        if (!"accepted".equalsIgnoreCase(event)) {
            log.info("[ActionPay] Postback ignored, event={}, subaccount={}", event, subaccount);
            return ResponseEntity.ok("ignored");
        }

        if (subaccount == null || subaccount.isBlank()) {
            return ResponseEntity.badRequest().body("missing subaccount");
        }
        long telegramId;
        try {
            telegramId = Long.parseLong(subaccount.trim());
        } catch (NumberFormatException e) {
            log.warn("[ActionPay] Postback rejected: subaccount is not a telegram id: {}", subaccount);
            return ResponseEntity.badRequest().body("bad subaccount");
        }

        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            log.warn("[ActionPay] Postback: user not found for telegramId={}", telegramId);
            return ResponseEntity.ok("user not found");
        }

        Quest quest = questRepository.findFirstByExternalAutoApproveTrueAndActiveTrue().orElse(null);
        if (quest == null) {
            log.error("[ActionPay] Postback received but no active external quest configured");
            return ResponseEntity.ok("no active external quest");
        }

        QuestSubmission existing = questService.getLatestSubmission(user, quest);
        boolean alreadyApproved = existing != null && existing.getStatus() == ru.gamebot.platform.domain.enums.SubmissionStatus.APPROVED;

        QuestSubmission submission = questService.approveExternalConversion(user, quest);
        log.info("[ActionPay] Postback processed for telegramId={}, uniqueid={}, submissionId={}, alreadyApproved={}",
                telegramId, uniqueid, submission.getId(), alreadyApproved);

        if (!alreadyApproved) {
            eventPublisher.publishEvent(new ExternalQuestApprovedEvent(this, submission.getId()));
        }

        return ResponseEntity.ok("ok");
    }
}
