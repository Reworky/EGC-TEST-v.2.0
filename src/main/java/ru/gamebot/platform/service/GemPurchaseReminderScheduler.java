package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.gamebot.platform.domain.enums.GemPurchaseStatus;
import ru.gamebot.platform.domain.model.GemPurchaseRequest;
import ru.gamebot.platform.domain.repository.GemPurchaseRequestRepository;
import ru.gamebot.platform.event.GemPurchaseStaleEvent;

/** Напоминает админам о заявках на донат без ответа (2026-10-07): с запуска доната все заявки были отклонены без выполнения,
 *  часть могла просто зависнуть. Одно напоминание на заявку (adminReminderSentAt), только по свежим заявкам (до 7 дней),
 *  чтобы после деплоя не залить админов старым бэклогом. */
@Slf4j
@Component
@RequiredArgsConstructor
public class GemPurchaseReminderScheduler {

    private static final long STALE_AFTER_HOURS = 2;
    private static final long MAX_AGE_DAYS = 7;

    private final GemPurchaseRequestRepository gemPurchaseRequestRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(fixedDelay = 1_800_000, initialDelay = 300_000)
    public void remindStale() {
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime staleBefore = now.minusHours(STALE_AFTER_HOURS);
            LocalDateTime oldest = now.minusDays(MAX_AGE_DAYS);
            List<GemPurchaseRequest> pending = gemPurchaseRequestRepository.findAllByStatusOrderByCreatedAtAsc(GemPurchaseStatus.PENDING);
            for (GemPurchaseRequest req : pending) {
                if (req.getAdminReminderSentAt() != null || req.getCreatedAt() == null) continue;
                if (req.getCreatedAt().isAfter(staleBefore) || req.getCreatedAt().isBefore(oldest)) continue;
                long hours = ChronoUnit.HOURS.between(req.getCreatedAt(), now);
                req.setAdminReminderSentAt(now);
                gemPurchaseRequestRepository.save(req);
                eventPublisher.publishEvent(new GemPurchaseStaleEvent(this, req.getId(), hours));
            }
        } catch (Exception e) {
            log.warn("Gem purchase reminder failed", e);
        }
    }
}
