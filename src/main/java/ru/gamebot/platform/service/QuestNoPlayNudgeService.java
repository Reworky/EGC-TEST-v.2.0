package ru.gamebot.platform.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.model.AppSetting;
import ru.gamebot.platform.domain.model.QuestSubmission;
import ru.gamebot.platform.domain.repository.AppSettingRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;
import ru.gamebot.platform.event.QuestNoPlayNudgeEvent;

/** Напоминание «взял квест, но не играл» (2026-10-10): по данным за 60 дней 17% взявших Brawl-квест на N боёв не сыграли ни одного боя после
 *  взятия - это две трети всех, кто не дошёл. Через {@link #DELAY_HOURS} ч после взятия, если прогресс всё ещё 0, игрок получает ОДНО сообщение
 *  (по заявке). Награду и эмиссию не меняет. Окно отправки - до {@link #MAX_AGE_HOURS} ч после взятия: если шлюз частоты отклонил напоминание
 *  (NotificationGateService), оно повторится на следующем тике, но не уйдёт запоздало. Не шлём ночью (часы по времени JVM, сервер - UTC: 06-19 UTC = 09-22 МСК).
 *  Откат без деплоя: app_settings 'quest.no_play_nudge.enabled' = false. */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestNoPlayNudgeService {

    public static final String SETTING_KEY = "quest.no_play_nudge.enabled";
    static final int DELAY_HOURS = 4;
    static final int MAX_AGE_HOURS = 24;
    private static final int SEND_FROM_HOUR = 6;
    private static final int SEND_UNTIL_HOUR = 19;

    private final QuestSubmissionRepository questSubmissionRepository;
    private final AppSettingRepository appSettingRepository;
    private final NotificationGateService notificationGate;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(fixedDelay = 900_000, initialDelay = 120_000)
    public void nudgeNotStarted() {
        LocalDateTime now = LocalDateTime.now();
        if (now.getHour() < SEND_FROM_HOUR || now.getHour() >= SEND_UNTIL_HOUR || !enabled()) {
            return;
        }
        List<QuestSubmission> candidates = questSubmissionRepository.findBrawlBattlesNotStarted(
                now.minusHours(DELAY_HOURS), now.minusHours(MAX_AGE_HOURS));
        for (QuestSubmission s : candidates) {
            try {
                if (s.getUser().isBlocked()) continue;
                if (!notificationGate.tryAcquire(s.getUser(), NudgeType.QUEST_NO_PLAY)) continue; // до отметки заявки: повтор на следующем тике
                s.setNoPlayNudgeSentAt(now);
                questSubmissionRepository.save(s);
                long minutesLeft = s.getExpiresAt() == null ? -1 : Math.max(0, Duration.between(now, s.getExpiresAt()).toMinutes());
                eventPublisher.publishEvent(new QuestNoPlayNudgeEvent(this, s.getUser().getTelegramId(), s.getQuest().getTitle(),
                        s.getQuest().getBrawlTargetCount(), s.getQuest().isBrawlRequireVictory(), minutesLeft));
            } catch (Exception e) {
                log.warn("Failed to process no-play nudge for submission {}", s.getId(), e);
            }
        }
    }

    private boolean enabled() {
        return appSettingRepository.findById(SETTING_KEY)
                .map(AppSetting::getValue)
                .map(v -> !"false".equalsIgnoreCase(v.trim()))
                .orElse(true);
    }
}
