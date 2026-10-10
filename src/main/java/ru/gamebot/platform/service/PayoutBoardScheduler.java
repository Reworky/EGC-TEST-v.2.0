package ru.gamebot.platform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.gamebot.platform.domain.model.AppSetting;
import ru.gamebot.platform.domain.repository.AppSettingRepository;
import ru.gamebot.platform.event.PayoutBoardRefreshEvent;

/** Раз в 10 минут просит бота обновить живое закреплённое сообщение «Лента выплат» в канале (2026-10-11). Работает только если админ включил ленту
 *  в «Коммуникации» (app_settings 'payout.board.enabled' = true); сам пост правится на месте, новых сообщений в канале не создаёт. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayoutBoardScheduler {

    public static final String ENABLED_KEY = "payout.board.enabled";

    private final AppSettingRepository appSettingRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(fixedDelay = 600_000, initialDelay = 120_000)
    public void refresh() {
        try {
            boolean enabled = appSettingRepository.findById(ENABLED_KEY)
                    .map(AppSetting::getValue)
                    .map(v -> "true".equalsIgnoreCase(v.trim()))
                    .orElse(false);
            if (enabled) {
                eventPublisher.publishEvent(new PayoutBoardRefreshEvent(this));
            }
        } catch (Exception e) {
            log.warn("Payout board refresh tick failed", e);
        }
    }
}
