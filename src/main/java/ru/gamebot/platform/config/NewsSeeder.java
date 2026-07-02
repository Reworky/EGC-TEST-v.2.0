package ru.gamebot.platform.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import ru.gamebot.platform.bot.GamePlatformBot;
import ru.gamebot.platform.domain.repository.NewsPostRepository;

@Slf4j
@Component
@Order(4)
@RequiredArgsConstructor
public class NewsSeeder implements CommandLineRunner {

    private static final String TITLE = "🎮 Большое обновление EGC — три новые механики";

    private final NewsPostRepository newsPostRepository;
    private final GamePlatformBot gamePlatformBot;

    @Override
    public void run(String... args) {
        boolean exists = newsPostRepository.findTop5ByActiveTrueOrderByPublishedAtDesc()
                .stream().anyMatch(p -> TITLE.equals(p.getTitle()));
        if (exists) return;

        String body = "🎁 <b>Ежедневный бонус за вход</b>\n"
                + "Каждый день заходи в бот и забирай EXC. Чем дольше серия без пропусков — тем больше награда. "
                + "На 7, 14, 30 и 90 день — особые бонусы.\n\n"
                + "🤝 <b>Мгновенный реферальный бонус</b>\n"
                + "Теперь и ты, и твой друг получаете EXC сразу при его регистрации — не нужно ждать первого квеста.\n\n"
                + "🏅 <b>Еженедельные лиги</b>\n"
                + "Каждую неделю игроки делятся на лиги по заработанному XP. В конце недели — EXC-призы: "
                + "от 300 до 5 000 в зависимости от лиги. Следи за своей лигой в разделе «Рейтинг».";

        gamePlatformBot.requestNewsApproval(TITLE, body);
        log.info("[NewsSeeder] Queued approval for update post '{}'", TITLE);
    }
}
