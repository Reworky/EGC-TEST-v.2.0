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
import ru.gamebot.platform.domain.model.RewardRequest;
import ru.gamebot.platform.event.PassPayoutOverdueEvent;

/** Обещание подписчикам EGC Pass: заявка на вывод обрабатывается в течение {@value #SLA_HOURS} часов. Раз в полчаса ищем заявки подписчиков,
 *  которые ждут дольше, и напоминаем админам (не чаще раза в {@value #REMIND_EVERY_HOURS} часов по одной заявке; память живёт до перезапуска бота -
 *  после перезапуска напоминание может прийти повторно, это безопасно). Ничего не меняет в самих заявках. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PassPayoutSlaService {

    public static final int SLA_HOURS = 12;
    private static final int REMIND_EVERY_HOURS = 6;

    private final RewardService rewardService;
    private final UserService userService;
    private final ApplicationEventPublisher eventPublisher;
    private final Map<Long, LocalDateTime> reminded = new ConcurrentHashMap<>();

    @Scheduled(fixedDelay = 1_800_000, initialDelay = 300_000)
    public void checkOverdue() {
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime overdueBefore = now.minusHours(SLA_HOURS);
            LocalDateTime remindBefore = now.minusHours(REMIND_EVERY_HOURS);
            List<Long> overdue = new ArrayList<>();
            for (RewardRequest r : rewardService.findPendingWithdrawals()) {
                if (r.getCreatedAt() == null || r.getCreatedAt().isAfter(overdueBefore)) continue;
                if (!userService.isEgcPassActive(r.getUser())) continue;
                LocalDateTime last = reminded.get(r.getId());
                if (last != null && last.isAfter(remindBefore)) continue;
                reminded.put(r.getId(), now);
                overdue.add(r.getId());
            }
            if (!overdue.isEmpty()) {
                eventPublisher.publishEvent(new PassPayoutOverdueEvent(this, overdue));
                log.info("[PassSla] {} Pass withdrawal request(s) overdue", overdue.size());
            }
        } catch (Exception e) {
            log.warn("[PassSla] check failed", e);
        }
    }
}
