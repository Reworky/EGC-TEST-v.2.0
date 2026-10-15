package ru.gamebot.platform.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.gamebot.platform.domain.model.UserActivityDay;
import ru.gamebot.platform.domain.repository.UserActivityDayRepository;

/** Пишет журнал активных дней игрока (см. {@link UserActivityDay}). Вызывается из UserService.touchBotActivity / touchMiniAppOpen на каждом
 *  действии игрока, поэтому первая проверка идёт по памяти: в БД ходим один раз на игрока в сутки. Запись в ОТДЕЛЬНОЙ транзакции (REQUIRES_NEW):
 *  вызов идёт из @Transactional-методов, а сбой (например, гонка двух апдейтов одного игрока на уникальном ключе) не должен помечать внешнюю
 *  транзакцию rollback-only (тот же урок, что в AdPlacementStatService). Журнал вторичен: любая ошибка только в лог. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityDayService {

    /** Сутки считаем по Москве, как во всех отчётах и в определениях ТЗ, независимо от часового пояса сервера. */
    public static final ZoneId MSK = ZoneId.of("Europe/Moscow");

    private final UserActivityDayRepository repository;
    private final PlatformTransactionManager transactionManager;

    private final Map<Long, LocalDate> loggedToday = new ConcurrentHashMap<>();

    public void record(Long userId, String source) {
        if (userId == null) return;
        LocalDate today = LocalDate.now(MSK);
        if (today.equals(loggedToday.get(userId))) return;
        try {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.executeWithoutResult(status -> {
                if (!repository.existsByUserIdAndDay(userId, today)) {
                    UserActivityDay row = new UserActivityDay();
                    row.setUserId(userId);
                    row.setDay(today);
                    row.setSource(source);
                    repository.save(row);
                }
            });
            loggedToday.put(userId, today);
        } catch (Exception e) {
            log.warn("Failed to record activity day for user {}", userId, e);
        }
    }
}
