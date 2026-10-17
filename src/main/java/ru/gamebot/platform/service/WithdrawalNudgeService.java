package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.WithdrawalNudge;
import ru.gamebot.platform.domain.repository.WithdrawalNudgeRepository;

/** A/B-рассылка «вывод у тебя уже открыт» (решение владельца 2026-10-17: слать только активным за 7 дней, сообщение согласовано).
 *  Группа назначается по чётности id игрока (A - чётные, получают сообщение; B - нечётные, контроль) и хранится в БД, чтобы
 *  через неделю сравнить долю подавших заявку и вернувшихся. Запускается админом командой /wdnudge, сама ничего не шлёт. */
@Service
@RequiredArgsConstructor
public class WithdrawalNudgeService {

    private final WithdrawalNudgeRepository repository;

    /** Назначает группы всем подходящим, у кого группы ещё нет; возвращает id группы A, которым ещё не отправлено и которые
     *  по-прежнему подходят (баланс/активность могли измениться с момента назначения). */
    @Transactional
    public List<Long> assignAndPendingGroupA() {
        List<Long> eligible = repository.findEligibleUserIds();
        Set<Long> eligibleSet = new HashSet<>(eligible);
        Set<Long> assigned = new HashSet<>();
        repository.findAll().forEach(n -> assigned.add(n.getId()));
        LocalDateTime now = LocalDateTime.now();
        List<WithdrawalNudge> created = new ArrayList<>();
        for (Long id : eligible) {
            if (assigned.contains(id)) continue;
            WithdrawalNudge n = new WithdrawalNudge();
            n.setId(id);
            n.setGrp(id % 2 == 0 ? "A" : "B");
            n.setAssignedAt(now);
            created.add(n);
        }
        repository.saveAll(created);
        List<Long> pending = new ArrayList<>();
        for (WithdrawalNudge n : repository.findBySentAtIsNullAndGrp("A")) {
            if (eligibleSet.contains(n.getId())) pending.add(n.getId());
        }
        return pending;
    }

    @Transactional
    public void markSent(Long userId) {
        repository.findById(userId).ifPresent(n -> {
            n.setSentAt(LocalDateTime.now());
            repository.save(n);
        });
    }

    public List<Object[]> stats() {
        return repository.statsByGroup();
    }

    public int eligibleCount() {
        return repository.findEligibleUserIds().size();
    }
}
