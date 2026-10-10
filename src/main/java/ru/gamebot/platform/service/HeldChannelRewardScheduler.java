package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.gamebot.platform.domain.model.QuestSubmission;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;
import ru.gamebot.platform.event.HeldChannelRewardDueEvent;

/** Раз в 30 минут передаёт боту удержанные награды за подписку на каналы спонсоров, срок которых вышел (2026-10-10).
 *  Порциями по 100 (самые старые первыми): после простоя бота бэклог разбирается за несколько прогонов без залпа проверок и сообщений. */
@Slf4j
@Component
@RequiredArgsConstructor
public class HeldChannelRewardScheduler {

    private final QuestSubmissionRepository questSubmissionRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(fixedDelay = 1_800_000, initialDelay = 600_000)
    public void processDue() {
        try {
            List<QuestSubmission> due = questSubmissionRepository
                    .findTop100ByHeldStatusAndHeldReleaseAtBeforeOrderByHeldReleaseAtAsc("PENDING", LocalDateTime.now());
            for (QuestSubmission submission : due) {
                eventPublisher.publishEvent(new HeldChannelRewardDueEvent(this, submission.getId()));
            }
        } catch (Exception e) {
            log.warn("Held channel reward processing failed", e);
        }
    }
}
