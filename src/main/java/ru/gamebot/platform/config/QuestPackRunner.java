package ru.gamebot.platform.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import ru.gamebot.platform.service.QuestPackService;

/** Последний шаг старта: после QuestSeeder возвращает квесты в состояние, заданное включённой пачкой каждой игры. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class QuestPackRunner implements CommandLineRunner {

    private final QuestPackService questPackService;

    @Override
    public void run(String... args) {
        questPackService.reconcileAll();
    }
}
