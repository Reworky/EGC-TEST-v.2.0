package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.model.QuestSubmission;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;
import ru.gamebot.platform.event.SponsorModerationOverdueEvent;

/** Спонсору платят за результат, поэтому заявки по спонсорским квестам с РУЧНОЙ проверкой (YouTube, TikTok и т.п.) не должны висеть: раз в полчаса находим
 *  заявки, которые ждут проверки дольше {@value #WAIT_HOURS} ч, и напоминаем админам и модераторам (по одной заявке не чаще раза в {@value #REMIND_EVERY_HOURS} ч;
 *  после перезапуска напоминание может прийти повторно, это безопасно). Ничего не меняет в самих заявках. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SponsorModerationReminderService {

    public static final int WAIT_HOURS = 2;
    private static final int REMIND_EVERY_HOURS = 6;

    private final QuestSubmissionRepository questSubmissionRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Map<Long, LocalDateTime> reminded = new ConcurrentHashMap<>();

    @Scheduled(fixedDelay = 1_800_000, initialDelay = 420_000)
    public void checkOverdue() {
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime remindBefore = now.minusHours(REMIND_EVERY_HOURS);
            List<Long> overdue = new ArrayList<>();
            for (QuestSubmission s : questSubmissionRepository.findPendingSponsoredManualBefore(now.minusHours(WAIT_HOURS))) {
                LocalDateTime last = reminded.get(s.getId());
                if (last != null && last.isAfter(remindBefore)) continue;
                reminded.put(s.getId(), now);
                overdue.add(s.getId());
            }
            if (!overdue.isEmpty()) {
                eventPublisher.publishEvent(new SponsorModerationOverdueEvent(this, overdue));
                log.info("[SponsorModeration] {} sponsored manual submission(s) wait for review", overdue.size());
            }
        } catch (Exception e) {
            log.warn("[SponsorModeration] check failed", e);
        }
    }
}
