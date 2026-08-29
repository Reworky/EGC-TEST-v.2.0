package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Tournament;
import ru.gamebot.platform.domain.model.TournamentEntry;
import ru.gamebot.platform.domain.repository.TournamentEntryRepository;
import ru.gamebot.platform.domain.repository.TournamentRepository;
import ru.gamebot.platform.event.BrawlStarsSnapshotBatchFailedEvent;
import ru.gamebot.platform.event.BrawlStarsSnapshotTakenEvent;

/**
 * Brawl Stars trophy-marathon specific logic: tag registration, trophy snapshots via the
 * official API, anti-cheat anomaly detection, and admin review actions. Kept separate from
 * TournamentService, which stays focused on generic tournament lifecycle.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrawlStarsTournamentService {

    private static final int ANOMALY_TROPHY_DROP_THRESHOLD = 300;
    private static final long BATCH_DELAY_MS = 180;

    private final BrawlStarsApiService brawlStarsApiService;
    private final TournamentEntryRepository tournamentEntryRepository;
    private final TournamentRepository tournamentRepository;
    private final UserService userService;
    private final ExcTransactionService excTx;
    private final ApplicationEventPublisher eventPublisher;

    // ---- Registration-time tag confirmation ----

    public record TagLookupResult(boolean success, String error, BrawlStarsApiService.PlayerInfo playerInfo) {}

    public TagLookupResult lookupTag(Tournament tournament, String rawTag) {
        if (!brawlStarsApiService.isEnabled()) {
            return new TagLookupResult(false, "Регистрация по тегу временно недоступна. Попробуйте позже.", null);
        }
        if (tournamentEntryRepository.existsByTournamentAndGameTag(tournament, normalizeDisplayTag(rawTag))) {
            return new TagLookupResult(false, "Этот тег уже зарегистрирован в этом турнире.", null);
        }
        try {
            Optional<BrawlStarsApiService.PlayerInfo> info = brawlStarsApiService.fetchPlayer(rawTag);
            if (info.isEmpty()) {
                return new TagLookupResult(false, "Тег не найден. Проверьте правильность (формат #ABC123).", null);
            }
            return new TagLookupResult(true, null, info.get());
        } catch (BrawlStarsApiService.BrawlStarsTransientException e) {
            log.warn("Brawl Stars tag lookup transient failure for tag={}", rawTag, e);
            return new TagLookupResult(false, "Сервис Brawl Stars временно недоступен. Попробуйте ещё раз чуть позже.", null);
        }
    }

    private String normalizeDisplayTag(String rawTag) {
        String t = rawTag.trim().toUpperCase();
        return t.startsWith("#") ? t : "#" + t;
    }

    /** Mirrors TournamentService.join()'s body, plus stores the confirmed tag/trophies. */
    @Transactional
    public TournamentService.JoinResult confirmAndJoin(AppUser user, Tournament tournament,
                                                         BrawlStarsApiService.PlayerInfo playerInfo) {
        if (tournament.getStatus() != Tournament.Status.REGISTRATION) {
            return new TournamentService.JoinResult(false, "Регистрация закрыта.");
        }
        if (tournamentEntryRepository.existsByTournamentAndUser(tournament, user)) {
            return new TournamentService.JoinResult(false, "Вы уже зарегистрированы.");
        }
        if (tournamentEntryRepository.existsByTournamentAndGameTag(tournament, playerInfo.tag())) {
            return new TournamentService.JoinResult(false, "Этот тег уже зарегистрирован в этом турнире.");
        }
        if (user.getCoins() < tournament.getEntryFeeExc()) {
            return new TournamentService.JoinResult(false, "Недостаточно EXC. Нужно: " + tournament.getEntryFeeExc());
        }

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
        entry.setGameTag(playerInfo.tag());
        entry.setTrophiesAtRegistration(playerInfo.trophies());
        tournamentEntryRepository.save(entry);

        return new TournamentService.JoinResult(true, null);
    }

    // ---- Batch snapshots (must be called OUTSIDE any open DB transaction — sequential network I/O) ----

    public void takeStartSnapshots(Tournament tournament) {
        if (tournament.getScoringType() != Tournament.ScoringType.BRAWL_TROPHIES) return;
        List<TournamentEntry> entries = tournamentEntryRepository.findAllWithUserByTournamentUnordered(tournament);
        runBatch(tournament, entries, true, "start");
    }

    public void takeEndSnapshots(Tournament tournament, List<TournamentEntry> entries) {
        if (tournament.getScoringType() != Tournament.ScoringType.BRAWL_TROPHIES) return;
        runBatch(tournament, entries, false, "end");
    }

    private void runBatch(Tournament tournament, List<TournamentEntry> entries, boolean isStart, String phase) {
        int failures = 0;
        for (TournamentEntry entry : entries) {
            if (!snapshotOne(entry, isStart)) failures++;
            sleepBetweenCalls();
        }
        if (!entries.isEmpty() && failures == entries.size()) {
            log.error("ALL {} snapshots failed for tournament {} ({} entries) — admin attention required",
                    phase, tournament.getId(), entries.size());
            eventPublisher.publishEvent(new BrawlStarsSnapshotBatchFailedEvent(this, tournament, phase));
        }
    }

    /**
     * Returns true on success. Checks isEnabled() first so a Brawl tournament degrades gracefully
     * (all entries end up FAILED, no crash) if the API token isn't configured yet.
     */
    @Transactional
    public boolean snapshotOne(TournamentEntry entry, boolean isStart) {
        if (!brawlStarsApiService.isEnabled()) {
            entry.setSnapshotStatus(TournamentEntry.SnapshotStatus.FAILED);
            tournamentEntryRepository.save(entry);
            log.warn("Skipping Brawl snapshot for entry {}: API disabled (no token configured)", entry.getId());
            return false;
        }
        if (entry.getGameTag() == null) {
            entry.setSnapshotStatus(TournamentEntry.SnapshotStatus.FAILED);
            tournamentEntryRepository.save(entry);
            return false;
        }
        try {
            Optional<BrawlStarsApiService.PlayerInfo> info = brawlStarsApiService.fetchPlayer(entry.getGameTag());
            if (info.isEmpty()) {
                entry.setSnapshotStatus(TournamentEntry.SnapshotStatus.FAILED);
                tournamentEntryRepository.save(entry);
                return false;
            }
            if (isStart) {
                entry.setTrophiesStart(info.get().trophies());
                checkAnomaly(entry);
                eventPublisher.publishEvent(new BrawlStarsSnapshotTakenEvent(this, entry));
            } else {
                entry.setTrophiesEnd(info.get().trophies());
            }
            entry.setSnapshotStatus(TournamentEntry.SnapshotStatus.OK);
            tournamentEntryRepository.save(entry);
            return true;
        } catch (BrawlStarsApiService.BrawlStarsTransientException e) {
            log.warn("Snapshot failed for entry {} (tag={}) after retries", entry.getId(), entry.getGameTag(), e);
            entry.setSnapshotStatus(TournamentEntry.SnapshotStatus.FAILED);
            tournamentEntryRepository.save(entry);
            return false;
        }
    }

    /**
     * Approximation: compares trophies at registration time vs. the official start snapshot
     * (registration close), not a strict rolling 24h window — there is no periodic polling
     * during registration in v1. Confirmed acceptable for v1.
     */
    private void checkAnomaly(TournamentEntry entry) {
        if (entry.getTrophiesAtRegistration() == null || entry.getTrophiesStart() == null) return;
        int drop = entry.getTrophiesAtRegistration() - entry.getTrophiesStart();
        if (drop > ANOMALY_TROPHY_DROP_THRESHOLD) {
            entry.setAnomalyFlag(true);
            entry.setPayoutHeld(true);
            log.warn("Anomaly flagged: entry {} tag={} dropped {} trophies since registration",
                    entry.getId(), entry.getGameTag(), drop);
        }
    }

    private void sleepBetweenCalls() {
        try { Thread.sleep(BATCH_DELAY_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    // ---- Scoring ----

    /** Score = trophy delta, or null if not scoreable (disqualified / snapshot missing or failed). */
    public Integer computeScore(TournamentEntry entry) {
        if (entry.isDisqualified()) return null;
        if (entry.getSnapshotStatus() != TournamentEntry.SnapshotStatus.OK) return null;
        if (entry.getTrophiesStart() == null || entry.getTrophiesEnd() == null) return null;
        return entry.getTrophiesEnd() - entry.getTrophiesStart();
    }

    // ---- Admin actions ----

    @Transactional
    public boolean reSnapshotEntry(Long entryId) {
        return tournamentEntryRepository.findById(entryId).map(entry -> {
            boolean isStartAttempt = entry.getTournament().getStatus() != Tournament.Status.FINISHED
                    && entry.getTrophiesStart() == null;
            return snapshotOne(entry, isStartAttempt);
        }).orElse(false);
    }

    @Transactional
    public void clearAnomaly(Long entryId) {
        tournamentEntryRepository.findById(entryId).ifPresent(entry -> {
            entry.setAnomalyResolved(true);
            tournamentEntryRepository.save(entry);
        });
    }

    @Transactional
    public void disqualify(Long entryId) {
        tournamentEntryRepository.findById(entryId).ifPresent(entry -> {
            entry.setDisqualified(true);
            entry.setAnomalyResolved(true);
            tournamentEntryRepository.save(entry);
        });
    }

    /** Idempotent: pays out only if currently held and not disqualified; flips payoutHeld exactly once. */
    @Transactional
    public boolean releaseHeldPayout(Long entryId) {
        return tournamentEntryRepository.findById(entryId).map(entry -> {
            if (!entry.isPayoutHeld() || entry.isDisqualified()) return false;
            if (entry.getPrizeExc() > 0) {
                AppUser user = entry.getUser();
                user.setCoins(user.getCoins() + entry.getPrizeExc());
                userService.save(user);
                excTx.log(user, entry.getPrizeExc(), ExcTransactionService.TOURNAMENT,
                        "Приз за турнир (флаг снят): " + entry.getTournament().getName());
            }
            entry.setPayoutHeld(false);
            tournamentEntryRepository.save(entry);
            return true;
        }).orElse(false);
    }
}
