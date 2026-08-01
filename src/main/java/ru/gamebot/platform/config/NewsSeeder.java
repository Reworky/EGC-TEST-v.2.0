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
        // no pending news
    }
}
