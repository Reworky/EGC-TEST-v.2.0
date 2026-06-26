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

        // ── Лёгкие ──────────────────────────────────────────────────────────────

        seed("Войти в топ-10 в Solo", "PUBG / PUBG Mobile",
                "Лёгкие", "PC, Mobile", 5, "5 дней", 25, 100,
                "Финишируй в топ-10 в любом матче Solo.",
                "Зайди в режим Solo → сыграй матч → доживи до топ-10.",
                "Скриншот экрана результатов: место ≤10, режим Solo, ник игрока.");

        seed("Убить 1 противника в матче", "PUBG / PUBG Mobile",
                "Лёгкие", "PC, Mobile", 3, "3 дня", 25, 100,
                "Уничтожь хотя бы 1 противника в любом матче.",
                "Сыграй матч в любом режиме → убей минимум 1 врага → дождись экрана итогов.",
                "Скриншот экрана результатов: счётчик убийств (Kills) ≥1, ник игрока.");

        seed("Выжить дольше 10 минут в матче", "PUBG / PUBG Mobile",
                "Лёгкие", "PC, Mobile", 3, "3 дня", 25, 100,
                "Продержись в матче не менее 10 минут — в любом режиме.",
                "Зайди в любой матч → играй минимум 10 минут → дождись экрана итогов с временем выживания.",
                "Скриншот экрана результатов: время выживания (Survival Time) ≥10:00, ник игрока.");

        // ── Средние ─────────────────────────────────────────────────────────────

        seed("Убить 3 противников в одном матче", "PUBG / PUBG Mobile",
                "Средние", "PC, Mobile", 5, "5 дней", 50, 200,
                "Набери 3 и более убийств за один матч в любом режиме.",
                "Сыграй матч → убей 3+ противников → дождись финального экрана.",
                "Скриншот экрана результатов: Kills ≥3, режим, ник игрока.");

        seed("Войти в топ-5 в Solo", "PUBG / PUBG Mobile",
                "Средние", "PC, Mobile", 7, "7 дней", 50, 200,
                "Финишируй в топ-5 игроков в матче Solo.",
                "Зайди в Solo → выживай как можно дольше → финишируй на 5-м месте или выше.",
                "Скриншот экрана результатов: место ≤5, режим Solo, ник игрока.");

        seed("Нанести 500+ урона в одном матче", "PUBG / PUBG Mobile",
                "Средние", "PC, Mobile", 7, "7 дней", 50, 200,
                "Нанеси суммарно 500 и более единиц урона за один матч.",
                "Сыграй матч → активно вступай в перестрелки → дождись экрана итогов с показателем урона.",
                "Скриншот экрана результатов: Damage Dealt ≥500, ник игрока.");

        seed("Победить в матче Squad (Chicken Dinner)", "PUBG / PUBG Mobile",
                "Средние", "PC, Mobile", 7, "7 дней", 50, 200,
                "Одержи победу (Winner Winner Chicken Dinner) в матче в режиме Squad.",
                "Зайди в Squad с командой → выиграй матч → дождись экрана победы.",
                "Скриншот экрана победы с надписью Winner Winner Chicken Dinner, режимом Squad, ником игрока.");

        seed("Убить противника из снайперской винтовки", "PUBG / PUBG Mobile",
                "Средние", "PC, Mobile", 7, "7 дней", 75, 250,
                "Убей хотя бы 1 противника из снайперской винтовки (AWM, Kar98k, M24, SLR и др.) в любом матче.",
                "Найди снайперскую винтовку → убей противника → дождись экрана Kill Feed или итогов матча.",
                "Скриншот Kill Feed или экрана результатов: видно убийство снайперским оружием (название оружия в строке Kill Feed), ник игрока.");

        // ── Сложные ─────────────────────────────────────────────────────────────

        seed("Победить в Solo (Chicken Dinner)", "PUBG / PUBG Mobile",
                "Сложные", "PC, Mobile", 14, "14 дней", 75, 300,
                "Одержи победу в Solo-матче — последний выживший на карте.",
                "Зайди в Solo → выживи до конца → стань последним игроком на карте.",
                "Скриншот экрана победы: Winner Winner Chicken Dinner, режим Solo, ник игрока.");

        seed("Убить 5 противников в одном матче", "PUBG / PUBG Mobile",
                "Сложные", "PC, Mobile", 10, "10 дней", 85, 350,
                "Набери 5 и более убийств за один матч в любом режиме.",
                "Сыграй матч → набери 5+ килов → дождись финального экрана результатов.",
                "Скриншот экрана результатов: Kills ≥5, режим, ник игрока.");

        seed("Нанести 1000+ урона в одном матче", "PUBG / PUBG Mobile",
                "Сложные", "PC, Mobile", 10, "10 дней", 85, 350,
                "Нанеси 1000 и более единиц урона за один матч.",
                "Играй агрессивно → набери 1000+ урона → дождись экрана итогов с показателем Damage Dealt.",
                "Скриншот экрана результатов: Damage Dealt ≥1000, ник игрока.");

        seed("Войти в топ-3 в Solo с 3+ убийствами", "PUBG / PUBG Mobile",
                "Сложные", "PC, Mobile", 14, "14 дней", 95, 400,
                "Финишируй в топ-3 в Solo-матче и при этом набери не менее 3 убийств.",
                "Зайди в Solo → играй агрессивно → финишируй в топ-3 с 3+ килами.",
                "Скриншот экрана результатов: место ≤3, Kills ≥3, режим Solo, ник игрока — всё на одном экране.");

        seed("Победить в Solo с 5+ убийствами", "PUBG / PUBG Mobile",
                "Сложные", "PC, Mobile", 14, "14 дней", 100, 500,
                "Выиграй Solo-матч и набери при этом 5 и более убийств — Chicken Dinner с доминированием.",
                "Зайди в Solo → играй агрессивно, не прячась → выиграй матч с 5+ убийствами.",
                "Скриншот экрана победы: Winner Winner Chicken Dinner, Kills ≥5, режим Solo, ник игрока — всё на одном экране.");
    }

    private void seed(String title, String gameName, String category, String platform,
                      int durationDays, String durationText,
                      long rewardXp, long rewardCoins,
                      String description, String instruction, String requirements) {
        if (questRepository.existsByTitleAndGameName(title, gameName)) {
            log.info("[QuestSeeder] Already exists: '{}'", title);
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
        quest.setParticipantLimit(100);
        quest.setActive(true);
        quest.setCouncilOnly(false);
        quest.setCreatedAt(LocalDateTime.now());
        questRepository.save(quest);
        log.info("[QuestSeeder] Created: '{}'", title);
    }
}
