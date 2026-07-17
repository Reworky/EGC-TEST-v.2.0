package ru.gamebot.platform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
        });
    }

    @Transactional
    public void deactivate(Long id) {
        sponsorRepository.findById(id).ifPresent(s -> {
            s.setActive(false);
            sponsorRepository.save(s);
        });
    }

    @Transactional
    public void deleteCampaign(Long id) {
        sponsorRepository.findById(id).ifPresent(s -> {
            // Unlink all quests
            questRepository.findAll().stream()
                    .filter(q -> id.equals(q.getSponsorId()))
                    .forEach(q -> {
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
