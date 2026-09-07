package ru.gamebot.platform.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.ReferralBoostEvent;
import ru.gamebot.platform.domain.repository.ReferralBoostEventRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Модуль 5 максимизации рефералки — временные окна, умножающие мгновенную реферальную
 * награду (+500/+300 EXC за активацию). Действует ТОЛЬКО на мгновенную награду, комиссия
 * 10% с квестов приглашённого не затрагивается — так сформулировано в ТЗ. */
@Service
@RequiredArgsConstructor
public class ReferralBoostService {

    private final ReferralBoostEventRepository repository;

    public Optional<ReferralBoostEvent> findActiveBoost() {
        LocalDateTime now = LocalDateTime.now();
        return repository.findFirstByActiveTrueAndStartAtBeforeAndEndAtAfterOrderByCreatedAtDesc(now, now);
    }

    public int currentMultiplier() {
        return findActiveBoost().map(ReferralBoostEvent::getMultiplier).orElse(1);
    }

    public List<ReferralBoostEvent> findAll() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    public Optional<ReferralBoostEvent> findById(Long id) {
        return repository.findById(id);
    }

    @Transactional
    public ReferralBoostEvent create(LocalDateTime startAt, LocalDateTime endAt, int multiplier) {
        ReferralBoostEvent event = new ReferralBoostEvent();
        event.setStartAt(startAt);
        event.setEndAt(endAt);
        event.setMultiplier(multiplier);
        event.setActive(true);
        event.setCreatedAt(LocalDateTime.now());
        return repository.save(event);
    }

    @Transactional
    public void setCustomAnnounceText(Long id, String text) {
        repository.findById(id).ifPresent(event -> {
            event.setCustomAnnounceText(text);
            repository.save(event);
        });
    }

    @Transactional
    public void updateStartAt(Long id, LocalDateTime startAt) {
        repository.findById(id).ifPresent(event -> {
            event.setStartAt(startAt);
            repository.save(event);
        });
    }

    @Transactional
    public void updateEndAt(Long id, LocalDateTime endAt) {
        repository.findById(id).ifPresent(event -> {
            event.setEndAt(endAt);
            repository.save(event);
        });
    }
}
