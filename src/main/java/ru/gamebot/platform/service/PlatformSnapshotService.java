package ru.gamebot.platform.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.PlatformSnapshot;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.PlatformSnapshotRepository;
import ru.gamebot.platform.domain.repository.QuestRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;
import ru.gamebot.platform.domain.repository.RewardRequestRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformSnapshotService {

    private final PlatformSnapshotRepository snapshotRepository;
    private final AppUserRepository appUserRepository;
    private final QuestSubmissionRepository questSubmissionRepository;
    private final RewardRequestRepository rewardRequestRepository;
    private final QuestRepository questRepository;

    @Transactional
    public PlatformSnapshot takeSnapshot() {
        LocalDate today = LocalDate.now();
        LocalDateTime nowDt = LocalDateTime.now();

        PlatformSnapshot snap = snapshotRepository.findBySnapshotDate(today)
                .orElse(new PlatformSnapshot());
        snap.setSnapshotDate(today);

        snap.setTotalUsers(appUserRepository.countByRegistrationCompletedTrue());
        snap.setNewUsersWeek(appUserRepository.countNewUsersSince(nowDt.minusDays(7)));
        snap.setActive7Days(appUserRepository.countActiveSince(today.minusDays(7)));
        snap.setActive30Days(appUserRepository.countActiveSince(today.minusDays(30)));
        snap.setTotalApprovedQuests(questSubmissionRepository.countAllApproved());
        snap.setApprovedQuestsMonth(questSubmissionRepository.countApprovedSince(nowDt.minusDays(30)));
        snap.setTotalPaidOutExc(rewardRequestRepository.sumApprovedWithdrawalExc());
        snap.setUniqueWithdrawalRecipients(rewardRequestRepository.countDistinctUsersWithApprovedWithdrawals());
        snap.setTotalCoinsOnAccounts(appUserRepository.sumAllCoins());
        snap.setTotalTickets(appUserRepository.sumAllTickets());
        snap.setActiveQuestsCount(questRepository.countByActiveTrue());

        // Воронка вовлечённости — те же формулы, что и в admin:stats:platform (когорта 7-14 / 30-60 дней назад)
        long cohort7 = appUserRepository.countRegisteredBetween(nowDt.minusDays(14), nowDt.minusDays(7));
        long retained7 = cohort7 > 0 ? appUserRepository.countRegisteredBetweenAndActiveSince(
                nowDt.minusDays(14), nowDt.minusDays(7), today.minusDays(7)) : 0;
        snap.setRetention7Cohort(cohort7);
        snap.setRetention7Pct(cohort7 > 0 ? retained7 * 100 / cohort7 : 0);

        long cohort30 = appUserRepository.countRegisteredBetween(nowDt.minusDays(60), nowDt.minusDays(30));
        long retained30 = cohort30 > 0 ? appUserRepository.countRegisteredBetweenAndActiveSince(
                nowDt.minusDays(60), nowDt.minusDays(30), today.minusDays(30)) : 0;
        snap.setRetention30Cohort(cohort30);
        snap.setRetention30Pct(cohort30 > 0 ? retained30 * 100 / cohort30 : 0);

        long moderated = questSubmissionRepository.countModerated();
        snap.setCompletionRatePct(moderated > 0 ? snap.getTotalApprovedQuests() * 100 / moderated : 0);

        snap.setCreatedAt(nowDt);

        PlatformSnapshot saved = snapshotRepository.save(snap);
        log.info("Platform snapshot saved: date={}, users={}, approved={}, paidOut={}",
                today, saved.getTotalUsers(), saved.getTotalApprovedQuests(), saved.getTotalPaidOutExc());
        return saved;
    }

    public Optional<PlatformSnapshot> getYesterday() {
        return snapshotRepository.findBySnapshotDate(LocalDate.now().minusDays(1));
    }

    public List<PlatformSnapshot> getHistory(int days) {
        return snapshotRepository.findTop30ByOrderBySnapshotDateDesc()
                .stream().limit(days).collect(Collectors.toList());
    }
}
