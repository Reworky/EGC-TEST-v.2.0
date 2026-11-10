package ru.gamebot.platform.service;

import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;

/** Быстрая проверка «игрок - партнёр» для списков (рейтинг, отряды, лента выплат, профиль): один запрос на всех, результат кэшируется на минуту. Метка «🤝» показывается только при включённых уровнях курса. */
@Service
@RequiredArgsConstructor
public class PartnerStatusService {

    private static final long TTL_MS = 60_000;

    private final QuestSubmissionRepository submissionRepository;
    private final HealthRatioService healthRatioService;

    private volatile Set<Long> cached = Set.of();
    private volatile long cachedAt = 0;

    /** AppUser.id входит в партнёров (одобренный квест из WithdrawalPartners.ALL) и уровни курса включены. */
    public boolean isPartnerUserId(Long userId) {
        if (userId == null || !healthRatioService.tiersEnabled()) return false;
        return partnerIds().contains(userId);
    }

    /** Метка « 🤝» (с пробелом впереди) для ника в списках; пусто, если игрок не партнёр. */
    public String mark(ru.gamebot.platform.domain.model.AppUser user) {
        return user != null && isPartnerUserId(user.getId()) ? " 🤝" : "";
    }

    private Set<Long> partnerIds() {
        long now = System.currentTimeMillis();
        if (now - cachedAt > TTL_MS) {
            Set<Long> ids = new HashSet<>();
            for (WithdrawalPartners.Bank bank : WithdrawalPartners.ALL) {
                ids.addAll(submissionRepository.findApprovedUserIdsByGame(bank.mission().gameName()));
            }
            cached = ids;
            cachedAt = now;
        }
        return cached;
    }
}
