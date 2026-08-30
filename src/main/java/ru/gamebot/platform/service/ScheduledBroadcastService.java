package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.enums.ScheduledBroadcastStatus;
import ru.gamebot.platform.domain.model.ScheduledBroadcast;
import ru.gamebot.platform.domain.repository.ScheduledBroadcastRepository;
import ru.gamebot.platform.event.ScheduledBroadcastDueEvent;

@Service
@RequiredArgsConstructor
public class ScheduledBroadcastService {

    private final ScheduledBroadcastRepository scheduledBroadcastRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public ScheduledBroadcast schedule(String text, String photoFileId, String caption,
                                        LocalDateTime scheduledAt, Long createdByTelegramId) {
        ScheduledBroadcast broadcast = new ScheduledBroadcast();
        broadcast.setText(text);
        broadcast.setPhotoFileId(photoFileId);
        broadcast.setCaption(caption);
        broadcast.setScheduledAt(scheduledAt);
        broadcast.setStatus(ScheduledBroadcastStatus.PENDING);
        broadcast.setCreatedByTelegramId(createdByTelegramId);
        broadcast.setCreatedAt(LocalDateTime.now());
        return scheduledBroadcastRepository.save(broadcast);
    }

    public List<ScheduledBroadcast> findPending() {
        return scheduledBroadcastRepository.findByStatusOrderByScheduledAtAsc(ScheduledBroadcastStatus.PENDING);
    }

    public ScheduledBroadcast getById(Long id) {
        return scheduledBroadcastRepository.findById(id).orElse(null);
    }

    @Transactional
    public boolean cancel(Long id) {
        return scheduledBroadcastRepository.findById(id)
                .filter(b -> b.getStatus() == ScheduledBroadcastStatus.PENDING)
                .map(b -> {
                    b.setStatus(ScheduledBroadcastStatus.CANCELLED);
                    scheduledBroadcastRepository.save(b);
                    return true;
                })
                .orElse(false);
    }

    /** Немедленно ставит уже запланированную рассылку в очередь на отправку, минуя scheduledAt. */
    public boolean triggerNow(Long id) {
        return scheduledBroadcastRepository.findById(id)
                .filter(b -> b.getStatus() == ScheduledBroadcastStatus.PENDING)
                .map(b -> {
                    eventPublisher.publishEvent(new ScheduledBroadcastDueEvent(this, id));
                    return true;
                })
                .orElse(false);
    }

    /** Только читает и публикует события — сам в Telegram не отправляет, это делает GamePlatformBot. */
    public void checkDue() {
        List<ScheduledBroadcast> due = scheduledBroadcastRepository.findByStatusAndScheduledAtBefore(
                ScheduledBroadcastStatus.PENDING, LocalDateTime.now());
        for (ScheduledBroadcast broadcast : due) {
            eventPublisher.publishEvent(new ScheduledBroadcastDueEvent(this, broadcast.getId()));
        }
    }

    @Transactional
    public void markSent(Long id, int deliveredCount) {
        scheduledBroadcastRepository.findById(id).ifPresent(b -> {
            b.setStatus(ScheduledBroadcastStatus.SENT);
            b.setSentAt(LocalDateTime.now());
            b.setDeliveredCount(deliveredCount);
            scheduledBroadcastRepository.save(b);
        });
    }
}
