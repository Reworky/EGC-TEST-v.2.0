package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.QuestRewardBoostEvent;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;
import ru.gamebot.platform.domain.repository.QuestRewardBoostEventRepository;

/** Глобальный буст EXC-наград за одобренные квесты (выходные ×2 и т.п.) — аналог ReferralBoostService,
 *  но действует на награду за квест для всех игроков сразу, а не на реферальный бонус одного. Создаётся
 *  автоматически по расписанию (см. WeeklyResetScheduler.startWeekendBoost), не вручную админом. */
@Service
@RequiredArgsConstructor
public class QuestRewardBoostService {

    /** Буст действует только на первые N одобренных квестов игрока за время буста (решение владельца 2026-09-24: +50% на
     * первые 3 вместо ×2 на все - иначе удвоение всей квестовой эмиссии на выходные стоило клубу порядка 5-6 тыс ₽/мес). */
    public static final int MAX_BOOSTED_QUESTS_PER_USER = 3;

    private final QuestRewardBoostEventRepository repository;
    private final QuestSubmissionRepository questSubmissionRepository;

    public Optional<QuestRewardBoostEvent> findActiveBoost() {
        LocalDateTime now = LocalDateTime.now();
        return repository.findFirstByActiveTrueAndStartAtBeforeAndEndAtAfterOrderByCreatedAtDesc(now, now);
    }

    public int currentBoostPercent() {
        return findActiveBoost().map(QuestRewardBoostEvent::getBoostPercent).orElse(0);
    }

    /** Процент буста для КОНКРЕТНОГО игрока: 0, если он уже получил буст за MAX_BOOSTED_QUESTS_PER_USER одобренных квестов
     * с начала буста. Награда считается ДО смены статуса квеста на APPROVED (см. QuestService.approveSubmission), поэтому
     * в счёт попадают только уже одобренные ранее квесты, а не текущий. */
    public int currentBoostPercentFor(AppUser user) {
        java.util.Optional<QuestRewardBoostEvent> boost = findActiveBoost();
        if (boost.isEmpty()) return 0;
        long done = questSubmissionRepository.countApprovedByUserBetween(user, boost.get().getStartAt(), LocalDateTime.now());
        return done < MAX_BOOSTED_QUESTS_PER_USER ? boost.get().getBoostPercent() : 0;
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
