package ru.gamebot.platform.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.enums.Cs2VerifyType;
import ru.gamebot.platform.domain.model.AppSetting;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.repository.AppSettingRepository;
import ru.gamebot.platform.domain.repository.QuestRepository;

/** 25.11.2026 (владелец): квесты CS2 «за один матч» (LAST_MATCH_*) не засчитываются - Steam не обновляет last_match_* и счётчик матчей.
 *  Скрываем их на каждом старте ПОСЛЕ остальных сидеров (Order 8), чтобы сидеры не включили их обратно.
 *  Вернуть: в app_settings ключ cs2.lastmatch.enabled = true (и перезапуск; сами квесты включить в админке). */
@Slf4j
@Component
@Order(8)
@RequiredArgsConstructor
public class Cs2LastMatchHider implements CommandLineRunner {

    static final String KEY = "cs2.lastmatch.enabled";

    private final QuestRepository questRepository;
    private final AppSettingRepository appSettingRepository;

    @Override
    @Transactional
    public void run(String... args) {
        try {
            String enabled = appSettingRepository.findById(KEY).map(AppSetting::getValue).orElse(null);
            if ("true".equalsIgnoreCase(enabled)) return;
            int hidden = 0;
            for (Quest q : questRepository.findAll()) {
                Cs2VerifyType t = q.getCs2VerifyType();
                if (t != null && t.name().startsWith("LAST_MATCH") && q.isActive()) {
                    q.setActive(false);
                    questRepository.save(q);
                    hidden++;
                }
            }
            if (hidden > 0) log.info("[Cs2LastMatchHider] Hidden {} CS2 per-match quests (Steam last_match_* not updating)", hidden);
        } catch (Exception e) {
            log.warn("[Cs2LastMatchHider] failed", e);
        }
    }
}
