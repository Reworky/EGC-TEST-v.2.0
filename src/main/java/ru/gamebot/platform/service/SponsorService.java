package ru.gamebot.platform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.model.Sponsor;
import ru.gamebot.platform.domain.repository.QuestRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;
import ru.gamebot.platform.domain.repository.SponsorRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SponsorService {

    private static final double COMMISSION_RATE = 0.30;

    private final SponsorRepository sponsorRepository;
    private final QuestRepository questRepository;
    private final QuestSubmissionRepository questSubmissionRepository;
    private final HealthRatioService healthRatioService;
    private final ApplicationEventPublisher eventPublisher;

    public List<Sponsor> findAll() { return sponsorRepository.findAllByOrderByCreatedAtDesc(); }
    public List<Sponsor> findActive() { return sponsorRepository.findAllByActiveTrueOrderByCreatedAtDesc(); }
    public Optional<Sponsor> findById(Long id) { return sponsorRepository.findById(id); }
    public Sponsor save(Sponsor s) { return sponsorRepository.save(s); }

    /**
     * Creates a sponsor campaign and optionally funds the Payout Pool with 70% of paidRub.
     * Commission (30%) stays with EGC operations.
     */
    @Transactional
    public Sponsor create(String name, String campaignName, long paidRub, long budgetExc,
                          LocalDateTime startDate, LocalDateTime endDate, Long adminTelegramId) {
        Sponsor s = new Sponsor();
        s.setName(name);
        s.setCampaignName(campaignName);
        s.setPaidRub(paidRub);
        s.setBudgetExc(budgetExc);
        s.setStartDate(startDate);
        s.setEndDate(endDate);
        s.setActive(true);
        s.setCreatedAt(LocalDateTime.now());
        s = sponsorRepository.save(s);

        // Auto-fund payout pool with 70% of sponsor payment
        if (paidRub > 0) {
            long poolAmount = Math.round(paidRub * (1 - COMMISSION_RATE));
            healthRatioService.addToPayoutPool(poolAmount, adminTelegramId);
            log.info("Sponsor '{}' funded payout pool: {}₽ (commission {}₽)",
                    name, poolAmount, paidRub - poolAmount);
        }

        return s;
    }

    @Transactional
    public void recordSpend(Long sponsorId, long excAmount) {
        sponsorRepository.findById(sponsorId).ifPresent(s -> {
            s.setSpentExc(s.getSpentExc() + excAmount);
            sponsorRepository.save(s);
            // Бюджет оплачен спонсором заранее и ограничен: как только выбран - кампания и её квесты выключаются сами
            // (раньше остаток просто показывался и квест продолжал платить из кармана клуба). Кампании под отчёт (budget=0) не трогаем.
            if (s.getBudgetExc() > 0 && s.isActive() && s.getSpentExc() >= s.getBudgetExc()) {
                deactivate(s.getId());
                eventPublisher.publishEvent(new ru.gamebot.platform.event.SponsorBudgetExhaustedEvent(
                        this, s.getId(), s.getName(), s.getBudgetExc(), s.getSpentExc()));
            }
        });
    }

    /** Возврат в бюджет части, уже учтённой как выданная, но не выплаченной игроку (не удержался в канале). Кампанию автоматически не включает. */
    @Transactional
    public void refundSpend(Long sponsorId, long excAmount) {
        if (excAmount <= 0) return;
        sponsorRepository.findById(sponsorId).ifPresent(s -> {
            s.setSpentExc(Math.max(0, s.getSpentExc() - excAmount));
            sponsorRepository.save(s);
        });
    }

    /** Удержание подписчиков по каналам кампании: всего пришло, вышли, удержание выплачено / сорвалось / ещё ждёт. */
    public record RetentionStats(long total, long left, long released, long forfeited, long pending) {
        public long stayed() { return Math.max(0, total - left); }
        public int stayedPercent() { return total == 0 ? 0 : (int) Math.round(stayed() * 100.0 / total); }
    }

    public RetentionStats retentionStats(Long sponsorId) {
        long total = 0, left = 0, released = 0, forfeited = 0, pending = 0;
        for (Quest q : findSponsoredQuests(sponsorId)) {
            if (q.getChannelCheckChatId() == null) continue;
            total += questSubmissionRepository.countApprovedByQuest(q);
            left += questSubmissionRepository.countLeftChannelByQuest(q);
            released += questSubmissionRepository.countByQuestAndHeldStatus(q, "RELEASED");
            forfeited += questSubmissionRepository.countByQuestAndHeldStatus(q, "FORFEITED");
            pending += questSubmissionRepository.countByQuestAndHeldStatus(q, "PENDING");
        }
        return new RetentionStats(total, left, released, forfeited, pending);
    }

    @Transactional
    public void deactivate(Long id) {
        sponsorRepository.findById(id).ifPresent(s -> {
            s.setActive(false);
            sponsorRepository.save(s);
            questRepository.findAll().stream()
                    .filter(q -> id.equals(q.getSponsorId()))
                    .forEach(q -> { q.setActive(false); questRepository.save(q); });
        });
    }

    @Transactional
    public void deleteCampaign(Long id) {
        sponsorRepository.findById(id).ifPresent(s -> {
            questRepository.findAll().stream()
                    .filter(q -> id.equals(q.getSponsorId()))
                    .forEach(q -> {
                        q.setActive(false);
                        q.setSponsored(false);
                        q.setSponsorId(null);
                        questRepository.save(q);
                    });
            sponsorRepository.delete(s);
        });
    }

    public List<Quest> findSponsoredQuests(Long sponsorId) {
        return questRepository.findAll().stream()
                .filter(q -> q.isSponsored() && sponsorId.equals(q.getSponsorId()))
                .toList();
    }

    public long remainingBudget(Sponsor s) {
        return Math.max(0, s.getBudgetExc() - s.getSpentExc());
    }

    public long commissionRub(Sponsor s) {
        return Math.round(s.getPaidRub() * COMMISSION_RATE);
    }

    /** Count approved submissions for sponsor's quests within the sponsor's period. */
    public long countCompletions(Sponsor sponsor) {
        if (sponsor.getStartDate() == null || sponsor.getEndDate() == null) {
            return findSponsoredQuests(sponsor.getId()).stream()
                    .mapToLong(questSubmissionRepository::countApprovedByQuest)
                    .sum();
        }
        LocalDateTime from = sponsor.getStartDate();
        LocalDateTime to = sponsor.getEndDate();
        return findSponsoredQuests(sponsor.getId()).stream()
                .mapToLong(q -> questSubmissionRepository.countApprovedByQuestBetween(q, from, to))
                .sum();
    }

    @Transactional
    public Sponsor createSimple(String name, String contact, Long questId,
                                LocalDate startDate, LocalDate endDate) {
        Sponsor s = new Sponsor();
        s.setName(name);
        s.setSponsorContact(contact);
        s.setBudgetExc(0);
        if (startDate != null) s.setStartDate(startDate.atStartOfDay());
        if (endDate != null) s.setEndDate(endDate.plusDays(1).atStartOfDay());
        s.setActive(true);
        s.setCreatedAt(LocalDateTime.now());
        Sponsor saved = sponsorRepository.save(s);

        if (questId != null) {
            questRepository.findById(questId).ifPresent(q -> {
                q.setSponsored(true);
                q.setSponsorId(saved.getId());
                questRepository.save(q);
            });
        }
        return saved;
    }
}
