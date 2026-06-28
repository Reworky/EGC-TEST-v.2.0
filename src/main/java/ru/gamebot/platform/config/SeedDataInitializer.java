package ru.gamebot.platform.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.service.NewsService;
import ru.gamebot.platform.service.QuestService;
import ru.gamebot.platform.service.RewardService;

@Component
@RequiredArgsConstructor
public class SeedDataInitializer implements CommandLineRunner {

    private final QuestService questService;
    private final RewardService rewardService;
    private final NewsService newsService;

    @Override
    public void run(String... args) {
        if (questService.findAll().isEmpty()) {
            seedQuests();
        }
        if (rewardService.findAvailableRewards().isEmpty()) {
            seedRewards();
        }
        if (newsService.latestNews().isEmpty()) {
            seedNews();
        }
    }

    private void seedQuests() {
        questService.createQuest(buildQuest(
                "Установить Cyber Drift",
                "Быстрое ознакомительное задание: установить игру, пройти авторизацию и дойти до первого боя.",
                "Cyber Drift",
                "Легкие",
                "Android / iPhone",
                "5-15 минут",
                40,
                80,
                "Сделайте скриншот экрана профиля после первого боя.",
                "Установить игру, пройти туториал, открыть профиль.",
                300
        ));
        questService.createQuest(buildQuest(
                "Взять 5 уровень в Kingdom Rise",
                "Средний квест для вовлечения: прокачайте аккаунт до 5 уровня и откройте сундук награды.",
                "Kingdom Rise",
                "Средние",
                "PC / Android",
                "1-3 дня",
                120,
                200,
                "Снимите видео открытия профиля с видимым уровнем 5+.",
                "Дойти до уровня 5, открыть вкладку персонажа.",
                120
        ));
        questService.createQuest(buildQuest(
                "Вступить в гильдию Star Arena",
                "Долгий квест с социальной механикой: вступите в активную гильдию и выполните первую совместную активность.",
                "Star Arena",
                "Сложные",
                "PC / PS5 / Xbox",
                "1-4 недели",
                300,
                500,
                "Пришлите видео или два скриншота: состав гильдии и лог совместной активности.",
                "Нужно вступить в гильдию и завершить первое событие.",
                60
        ));
    }

    private Quest buildQuest(String title, String description, String game, String category, String platform,
                             String duration, long xp, long coins, String instruction, String requirements,
                             int limit) {
        Quest quest = new Quest();
        quest.setTitle(title);
        quest.setDescription(description);
        quest.setGameName(game);
        quest.setCategory(category);
        quest.setPlatform(platform);
        quest.setDurationText(duration);
        quest.setRewardXp(xp);
        quest.setRewardCoins(coins);
        quest.setInstruction(instruction);
        quest.setRequirements(requirements);
        quest.setParticipantLimit(limit);
        return quest;
    }

    private void seedRewards() {
        // Подарочные карты
        rewardService.createRewardItem(
                "Steam Gift Card 100 руб.",
                "Цифровой код пополнения баланса Steam на 100 рублей. Высылается в течение 24 часов.",
                "Подарочные карты",
                2000
        );
        rewardService.createRewardItem(
                "Steam Gift Card 200 руб.",
                "Цифровой код пополнения баланса Steam на 200 рублей. Высылается в течение 24 часов.",
                "Подарочные карты",
                4000
        );
        rewardService.createRewardItem(
                "Steam Gift Card 500 руб.",
                "Цифровой код пополнения баланса Steam на 500 рублей. Высылается в течение 24 часов.",
                "Подарочные карты",
                9000
        );
        rewardService.createRewardItem(
                "PSN Gift Card 200 руб.",
                "Цифровой код пополнения кошелька PlayStation Network на 200 рублей.",
                "Подарочные карты",
                4000
        );

        // Игровые подписки
        rewardService.createRewardItem(
                "Xbox Game Pass Ultimate 1 мес.",
                "Подписка Xbox Game Pass Ultimate на 1 месяц: более 100 игр + EA Play + онлайн.",
                "Игровые подписки",
                5000
        );
        rewardService.createRewardItem(
                "PlayStation Plus Essential 1 мес.",
                "Подписка PlayStation Plus Essential на 1 месяц: онлайн-игра + ежемесячные игры.",
                "Игровые подписки",
                5000
        );

        // Мерч клуба
        rewardService.createRewardItem(
                "Стикерпак EGC",
                "Набор из 10 фирменных стикеров Experience Gaming Club. Доставка по договорённости.",
                "Мерч клуба",
                3000
        );
        rewardService.createRewardItem(
                "Футболка EGC",
                "Фирменная футболка Experience Gaming Club. Уточните размер при оформлении заявки.",
                "Мерч клуба",
                8000
        );
    }

    private void seedNews() {
        newsService.createPost(
                "Новая подборка быстрых квестов",
                "⚡ В витрину добавлены задания на Android и PC, которые можно закрыть меньше чем за 20 минут."
        );
        newsService.createPost(
                "Реферальный спринт недели",
                "🤝 За каждого активного приглашенного друга сейчас начисляется +30 XP и +50 монет."
        );
    }
}
