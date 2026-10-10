package ru.gamebot.platform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.model.AppSetting;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.model.QuestSubmission;
import ru.gamebot.platform.domain.repository.AppSettingRepository;
import ru.gamebot.platform.event.QuestProgressMilestoneEvent;

/** Ступени прогресса автоквестов (2026-10-10, идея из Mistplay - чекпойнты): игроку приходит сообщение на половине пути и на последнем
 *  шаге. Награду и эмиссию не меняет. Только квесты с целью от {@link #MIN_TARGET} - на коротких («Сыграй 3 боя») ступени были бы спамом.
 *  Откат без деплоя: app_settings 'quest.progress_milestones.enabled' = false. Не бросает исключений - проверка квеста важнее уведомления. */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestProgressNotifier {

    public static final String SETTING_KEY = "quest.progress_milestones.enabled";
    static final int MIN_TARGET = 5;

    private final AppSettingRepository appSettingRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** Вызывать сразу после обновления прогресса и ДО сохранения заявки: метод может записать в неё отправленную ступень. */
    public void onProgress(QuestSubmission submission, Quest quest, long progress, long target) {
        try {
            if (target < MIN_TARGET || progress <= 0 || progress >= target) {
                return; // выполнение обрабатывает сам сервис проверки
            }
            long remaining = target - progress;
            long lastStepRemaining = Math.max(1, target / 10);
            int level = remaining <= lastStepRemaining ? 2 : (progress * 2 >= target ? 1 : 0);
            if (level == 0 || level <= submission.getProgressMilestoneSent() || !enabled()) {
                return;
            }
            submission.setProgressMilestoneSent(level);
            eventPublisher.publishEvent(new QuestProgressMilestoneEvent(
                    this, submission.getUser().getTelegramId(), quest.getTitle(), progress, target, level));
        } catch (Exception e) {
            log.warn("Quest progress milestone failed for submission {}", submission.getId(), e);
        }
    }

    private boolean enabled() {
        return appSettingRepository.findById(SETTING_KEY)
                .map(AppSetting::getValue)
                .map(v -> !"false".equalsIgnoreCase(v.trim()))
                .orElse(true);
    }
}
