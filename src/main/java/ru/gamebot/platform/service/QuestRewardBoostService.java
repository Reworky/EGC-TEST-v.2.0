package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.QuestRewardBoostEvent;
import ru.gamebot.platform.domain.repository.QuestRewardBoostEventRepository;

/** Глобальный буст EXC-наград за одобренные квесты (выходные ×2 и т.п.) — аналог ReferralBoostService,
 *  но действует на награду за квест для всех игроков сразу, а не на реферальный бонус одного. Создаётся
 *  автоматически по расписанию (см. WeeklyResetScheduler.startWeekendBoost), не вручную админом. */
@Service
@RequiredArgsConstructor
public class QuestRewardBoostService {

    private final QuestRewardBoostEventRepository repository;

    public Optional<QuestRewardBoostEvent> findActiveBoost() {
        LocalDateTime now = LocalDateTime.now();
        return repository.findFirstByActiveTrueAndStartAtBeforeAndEndAtAfterOrderByCreatedAtDesc(now, now);
    }

    public int currentBoostPercent() {
        return findActiveBoost().map(QuestRewardBoostEvent::getBoostPercent).orElse(0);
    }

    @Transactional
    public QuestRewardBoostEvent create(LocalDateTime startAt, LocalDateTime endAt, int boostPercent) {
        QuestRewardBoostEvent event = new QuestRewardBoostEvent();
        event.setStartAt(startAt);
        event.setEndAt(endAt);
        event.setBoostPercent(boostPercent);
        event.setActive(true);
        event.setCreatedAt(LocalDateTime.now());
        return repository.save(event);
    }
}
