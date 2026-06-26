package ru.gamebot.platform.config;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.repository.QuestRepository;

@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class QuestSeeder implements CommandLineRunner {

    private final QuestRepository questRepository;

    @Override
    public void run(String... args) {
        seed("Войти в топ-10 в Solo", "PUBG / PUBG Mobile",
                "Лёгкие", "PC, Mobile", 5, "5 дней",
                25, 100,
                "Финишируй в топ-10 в любом матче Solo.",
                "Зайди в режим Solo → сыграй матч → доживи до топ-10.",
                "Скриншот экрана результатов: место ≤10, режим Solo, ник игрока.",
                100);
        seed("Убить 1 противника в матче", "PUBG / PUBG Mobile",
                "Лёгкие", "PC, Mobile", 3, "3 дня",
                25, 100,
                "Уничтожь хотя бы 1 противника в любом матче.",
                "Сыграй матч в любом режиме → убей минимум 1 врага → дождись экрана итогов.",
                "Скриншот экрана результатов: счётчик убийств (Kills) ≥1, ник игрока.",
                100);
    }

    private void seed(String title, String gameName, String category, String platform,
                      int durationDays, String durationText,
                      long rewardXp, long rewardCoins,
                      String description, String instruction, String requirements,
                      int limit) {
        if (questRepository.existsByTitleAndGameName(title, gameName)) {
            log.info("[QuestSeeder] Quest already exists: '{}'", title);
            return;
        }
        Quest quest = new Quest();
        quest.setTitle(title);
        quest.setGameName(gameName);
        quest.setCategory(category);
        quest.setPlatform(platform);
        quest.setDurationDays(durationDays);
        quest.setDurationText(durationText);
        quest.setRewardXp(rewardXp);
        quest.setRewardCoins(rewardCoins);
        quest.setDescription(description);
        quest.setInstruction(instruction);
        quest.setRequirements(requirements);
        quest.setParticipantLimit(limit);
        quest.setActive(true);
        quest.setCouncilOnly(false);
        quest.setCreatedAt(LocalDateTime.now());
        questRepository.save(quest);
        log.info("[QuestSeeder] Created quest: '{}'", title);
    }
}
