package ru.gamebot.platform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Tournament;
import ru.gamebot.platform.domain.model.TournamentEntry;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;
import ru.gamebot.platform.domain.repository.TournamentEntryRepository;
import ru.gamebot.platform.domain.repository.TournamentRepository;
import ru.gamebot.platform.event.TournamentFinishedEvent;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TournamentService {

    private static final int MAX_RANKED = 10;

    private final TournamentRepository tournamentRepository;
    private final TournamentEntryRepository tournamentEntryRepository;
    private final QuestSubmissionRepository questSubmissionRepository;
    private final UserService userService;
    private final ApplicationEventPublisher eventPublisher;
    private final ExcTransactionService excTx;

    public Optional<Tournament> findActive() {
        return tournamentRepository.findFirstByStatusOrderByCreatedAtDesc(Tournament.Status.ACTIVE);
    }

    public Optional<Tournament> findRegistration() {
        return tournamentRepository.findFirstByStatusOrderByCreatedAtDesc(Tournament.Status.REGISTRATION);
    }

    public Optional<Tournament> findCurrentForUser() {
        Optional<Tournament> reg = findRegistration();
        if (reg.isPresent()) return reg;
        return findActive();
    }

    public List<Tournament> findAll() {
        return tournamentRepository.findAllByOrderByCreatedAtDesc();
    }

    public Optional<Tournament> findById(Long id) {
        return tournamentRepository.findById(id);
    }

    public boolean hasEntered(Tournament tournament, AppUser user) {
        return tournamentEntryRepository.existsByTournamentAndUser(tournament, user);
    }

    public long entryCount(Tournament tournament) {
        return tournamentEntryRepository.countByTournament(tournament);
    }

    public List<TournamentEntry> getLeaderboard(Tournament tournament) {
        return tournamentEntryRepository.findAllWithUserByTournament(tournament);
    }

    public record JoinResult(boolean success, String error) {}

    @Transactional
    public JoinResult join(AppUser user, Tournament tournament) {
        if (tournament.getStatus() != Tournament.Status.REGISTRATION)
            return new JoinResult(false, "Регистрация закрыта.");
        if (hasEntered(tournament, user))
            return new JoinResult(false, "Вы уже зарегистрированы.");
        if (user.getCoins() < tournament.getEntryFeeExc())
            return new JoinResult(false, "Недостаточно EXC. Нужно: " + tournament.getEntryFeeExc());

        user.setCoins(user.getCoins() - tournament.getEntryFeeExc());
        tournament.setPrizePoolExc(tournament.getPrizePoolExc() + tournament.getEntryFeeExc());
        userService.save(user);
        excTx.log(user, -tournament.getEntryFeeExc(), ExcTransactionService.TOURNAMENT, "Взнос за турнир: " + tournament.getName());
        tournamentRepository.save(tournament);

        TournamentEntry entry = new TournamentEntry();
        entry.setTournament(tournament);
        entry.setUser(user);
        entry.setEntryFeeExc(tournament.getEntryFeeExc());
        entry.setCreatedAt(LocalDateTime.now());
        tournamentEntryRepository.save(entry);

        return new JoinResult(true, null);
    }

    @Transactional
    public Tournament create(String name, String gameName, long entryFeeExc, LocalDateTime startDate, LocalDateTime endDate) {
        Tournament t = new Tournament();
        t.setName(name);
        t.setGameName(gameName);
        t.setEntryFeeExc(entryFeeExc);
        t.setStartDate(startDate);
        t.setEndDate(endDate);
        t.setStatus(Tournament.Status.REGISTRATION);
        t.setCreatedAt(LocalDateTime.now());
        return tournamentRepository.save(t);
    }

    @Transactional
    public void activateRegistrationTournaments() {
        List<Tournament> regs = tournamentRepository.findAllByStatusOrderByCreatedAtDesc(Tournament.Status.REGISTRATION);
        for (Tournament t : regs) {
            if (t.getStartDate() != null && LocalDateTime.now().isAfter(t.getStartDate())) {
                t.setStatus(Tournament.Status.ACTIVE);
                tournamentRepository.save(t);
                log.info("Tournament {} started", t.getId());
            }
        }
    }

    @Transactional
    public void settleFinishedTournaments() {
        List<Tournament> active = tournamentRepository.findAllByStatusOrderByCreatedAtDesc(Tournament.Status.ACTIVE);
        for (Tournament t : active) {
            if (t.getEndDate() != null && LocalDateTime.now().isAfter(t.getEndDate())) {
                settle(t);
            }
        }
    }

    private void settle(Tournament tournament) {
        List<TournamentEntry> entries = tournamentEntryRepository.findAllWithUserByTournament(tournament);
        if (entries.isEmpty()) {
            tournament.setStatus(Tournament.Status.FINISHED);
            tournamentRepository.save(tournament);
            return;
        }

        // Count quests approved during tournament window for each participant
        record EntryScore(TournamentEntry entry, long score) {}
        List<EntryScore> scored = new ArrayList<>();
        for (TournamentEntry e : entries) {
            long score = questSubmissionRepository.countApprovedByUserBetween(
                    e.getUser(), tournament.getStartDate(), tournament.getEndDate());
            scored.add(new EntryScore(e, score));
        }
        scored.sort(Comparator.comparingLong(EntryScore::score).reversed());

        long pool = tournament.getPrizePoolExc();
        int top = Math.min(MAX_RANKED, scored.size());

        for (int i = 0; i < scored.size(); i++) {
            TournamentEntry entry = scored.get(i).entry();
            entry.setRank(i + 1);

            long prize = 0;
            if (i == 0 && top > 0) {
                prize = (long) (pool * 0.60);
            } else if (i > 0 && i < top) {
                long rest = pool - (long) (pool * 0.60);
                prize = rest / (top - 1);
            }
            entry.setPrizeExc(prize);
            if (prize > 0) {
                AppUser user = entry.getUser();
                user.setCoins(user.getCoins() + prize);
                userService.save(user);
                excTx.log(user, prize, ExcTransactionService.TOURNAMENT, "Приз за турнир: " + tournament.getName() + " (#" + (i + 1) + " место)");
            }
            tournamentEntryRepository.save(entry);
        }

        tournament.setStatus(Tournament.Status.FINISHED);
        tournamentRepository.save(tournament);

        eventPublisher.publishEvent(new TournamentFinishedEvent(this, tournament, entries));
        log.info("Tournament {} settled. Pool={} EXC, participants={}", tournament.getId(), pool, entries.size());
    }
}
