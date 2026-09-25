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
import ru.gamebot.platform.event.TournamentCancelledEvent;
import ru.gamebot.platform.event.TournamentFinishedEvent;
import ru.gamebot.platform.event.TournamentSeasonWarningEvent;
import ru.gamebot.platform.event.TournamentStageEvent;

import java.time.Duration;
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

    /** Окно регистрации для авто-продолженного турнира (см. autoCreateNextTournament). Без него новый
     * турнир активировался бы на следующем тике планировщика (~60 сек после создания) — у игроков
     * физически не было бы времени зарегистрироваться (инцидент 2026-09-14: обнаружено на живом
     * Brawl Stars турнире). 72 часа — явное решение пользователя 2026-09-14 (было 24ч). */
    private static final Duration AUTO_CONTINUATION_REGISTRATION_WINDOW = Duration.ofHours(72);

    private final TournamentRepository tournamentRepository;
    private final TournamentEntryRepository tournamentEntryRepository;
    private final QuestSubmissionRepository questSubmissionRepository;
    private final UserService userService;
    private final ApplicationEventPublisher eventPublisher;
    private final ExcTransactionService excTx;
    private final TrophyTournamentService trophyTournamentService;

    public Optional<Tournament> findActive() {
        return tournamentRepository.findFirstByStatusOrderByCreatedAtDesc(Tournament.Status.ACTIVE);
    }

    /** Только турниры, у которых регистрация уже открыта: запланированные с отложенным открытием игроку не видны. */
    public Optional<Tournament> findRegistration() {
        return tournamentRepository.findAllByStatusOrderByCreatedAtDesc(Tournament.Status.REGISTRATION).stream()
                .filter(Tournament::isRegistrationOpen).findFirst();
    }

    public Optional<Tournament> findCurrentForUser() {
        Optional<Tournament> reg = findRegistration();
        if (reg.isPresent()) return reg;
        return findActive();
    }

    /** Все текущие турниры для игрока: сначала с открытой регистрацией, затем идущие (новые выше). Нужен, потому что
     *  турниры по разным играм (Brawl Stars и Clash Royale) идут параллельно, а findCurrentForUser() отдаёт только один. */
    public List<Tournament> findAllCurrentForUser() {
        List<Tournament> result = new ArrayList<>(
                tournamentRepository.findAllByStatusOrderByCreatedAtDesc(Tournament.Status.REGISTRATION).stream()
                        .filter(Tournament::isRegistrationOpen).toList());
        result.addAll(tournamentRepository.findAllByStatusOrderByCreatedAtDesc(Tournament.Status.ACTIVE));
        return result;
    }

    public List<Tournament> findAll() {
        return tournamentRepository.findAllByOrderByCreatedAtDesc();
    }

    public Optional<Tournament> findById(Long id) {
        return tournamentRepository.findById(id);
    }

    /** Точечное редактирование name/description/photoFileId уже созданного турнира (баннер, описание,
     * название) — отдельно от wizard'а create(), не трогает даты/взнос/статус. Нужно в первую очередь
     * для авто-продолженных турниров: они клонируются раз за разом (см. autoCreateNextTournament), и
     * админу нужно поправить оформление один раз, не пересоздавая турнир вручную. */
    public Tournament save(Tournament tournament) {
        return tournamentRepository.save(tournament);
    }

    @Transactional
    public void delete(Long id) {
        tournamentRepository.findById(id).ifPresent(t -> {
            tournamentEntryRepository.deleteAllByTournament(t);
            tournamentRepository.delete(t);
        });
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

    /** Число одобренных квестов участника в окне турнира — та же метрика, что settle() использует
     * для ранжирования QUEST_COUNT-турниров, но не сохраняется на TournamentEntry, поэтому для показа
     * в итоговом сообщении (см. GamePlatformBot.onTournamentFinished) считается заново по запросу. */
    public long questScoreDuring(Tournament tournament, AppUser user) {
        return questSubmissionRepository.countApprovedByUserBetween(user, tournament.getStartDate(), tournament.getEndDate());
    }

    public record JoinResult(boolean success, String error) {}

    @Transactional
    public JoinResult join(AppUser user, Tournament tournament) {
        if (tournament.getScoringType().isTrophyRace())
            return new JoinResult(false, "Регистрация на этот турнир — только через Telegram-бота (нужен игровой тег).");
        if (tournament.getStatus() != Tournament.Status.REGISTRATION)
            return new JoinResult(false, "Регистрация закрыта.");
        if (!tournament.isRegistrationOpen())
            return new JoinResult(false, "Регистрация ещё не открыта.");
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
        return create(name, null, gameName, entryFeeExc, startDate, endDate, null, null);
    }

    @Transactional
    public Tournament create(String name, String gameName, long entryFeeExc, LocalDateTime startDate, LocalDateTime endDate,
                              Integer minParticipants, String photoFileId) {
        return create(name, null, gameName, entryFeeExc, startDate, endDate, minParticipants, photoFileId);
    }

    @Transactional
    public Tournament create(String name, String description, String gameName, long entryFeeExc, LocalDateTime startDate, LocalDateTime endDate,
                              Integer minParticipants, String photoFileId) {
        Tournament t = new Tournament();
        t.setName(name);
        t.setDescription(description);
        t.setGameName(gameName);
        t.setEntryFeeExc(entryFeeExc);
        t.setStartDate(startDate);
        t.setEndDate(endDate);
        t.setStatus(Tournament.Status.REGISTRATION);
        t.setScoringType(Tournament.ScoringType.forGame(gameName));
        t.setMinParticipants(minParticipants);
        t.setPhotoFileId(photoFileId);
        t.setCreatedAt(LocalDateTime.now());
        return tournamentRepository.save(t);
    }

    /**
     * No blanket @Transactional here: Brawl Stars tournaments trigger a sequential batch of
     * outbound HTTP calls (trophy snapshots) that must not run inside an open DB transaction.
     * Each individual repository call below is already transactional on its own (Spring Data JPA
     * wraps every save()/findAllBy...() call), so the status flip is still safely persisted —
     * this method just no longer wraps the whole loop plus network I/O in one big transaction.
     */
    public void activateRegistrationTournaments() {
        List<Tournament> regs = tournamentRepository.findAllByStatusOrderByCreatedAtDesc(Tournament.Status.REGISTRATION);
        List<Tournament> justActivated = new ArrayList<>();
        for (Tournament t : regs) {
            if (t.getStartDate() != null && LocalDateTime.now().isAfter(t.getStartDate())) {
                long entryCount = tournamentEntryRepository.countByTournament(t);
                if (t.getMinParticipants() != null && entryCount < t.getMinParticipants()) {
                    cancelForLowTurnout(t);
                    continue;
                }
                t.setStatus(Tournament.Status.ACTIVE);
                tournamentRepository.save(t);
                log.info("Tournament {} started", t.getId());
                justActivated.add(t);
            }
        }
        for (Tournament t : justActivated) {
            if (t.getScoringType().isTrophyRace()) {
                trophyTournamentService.takeStartSnapshots(t);
            }
        }
    }

    /** Пост о «свежем» этапе имеет смысл, только пока он свежий: у турниров, начавшихся давно (созданных до этой функции),
     *  этап молча помечается как объявленный, без карточки админу - иначе после деплоя пришёл бы залп устаревших постов. */
    private static final Duration STAGE_ANNOUNCE_MAX_AGE = Duration.ofHours(6);

    /** Готовит посты на одобрение админа: «регистрация открыта» и «турнир стартовал (регистрация закрыта)». Вызывается тем
     *  же тиком планировщика после activateRegistrationTournaments(), поэтому к «старту» стартовые снимки уже взяты.
     *  Итоги турнира готовит settle() (TournamentFinishedEvent). No blanket @Transactional - как и соседние методы. */
    public void announceTournamentStages() {
        LocalDateTime now = LocalDateTime.now();
        for (Tournament t : tournamentRepository.findAllByStatusOrderByCreatedAtDesc(Tournament.Status.REGISTRATION)) {
            if (t.isRegistrationAnnounced() || !t.isRegistrationOpen()) continue;
            LocalDateTime openedAt = t.getRegistrationOpenDate() != null ? t.getRegistrationOpenDate() : t.getCreatedAt();
            t.setRegistrationAnnounced(true);
            tournamentRepository.save(t);
            if (openedAt == null || Duration.between(openedAt, now).compareTo(STAGE_ANNOUNCE_MAX_AGE) <= 0) {
                eventPublisher.publishEvent(new TournamentStageEvent(this, t, TournamentStageEvent.Stage.REGISTRATION_OPENED));
            }
        }
        for (Tournament t : tournamentRepository.findAllByStatusOrderByCreatedAtDesc(Tournament.Status.ACTIVE)) {
            if (t.isStartAnnounced()) continue;
            t.setStartAnnounced(true);
            tournamentRepository.save(t);
            if (t.getStartDate() == null || Duration.between(t.getStartDate(), now).compareTo(STAGE_ANNOUNCE_MAX_AGE) <= 0) {
                eventPublisher.publishEvent(new TournamentStageEvent(this, t, TournamentStageEvent.Stage.TOURNAMENT_STARTED));
            }
        }
    }

    /**
     * Registration closed without reaching minParticipants: cancel instead of activating,
     * refund every registered entry's fee (idempotent via entry.refunded), no start snapshot is taken.
     * Does NOT auto-continue — a tournament that failed to reach its minimum shouldn't silently relaunch itself.
     */
    private void cancelForLowTurnout(Tournament t) {
        t.setStatus(Tournament.Status.CANCELLED_LOW_TURNOUT);
        tournamentRepository.save(t);

        List<TournamentEntry> entries = tournamentEntryRepository.findAllWithUserByTournament(t);
        List<TournamentEntry> refunded = new ArrayList<>();
        for (TournamentEntry e : entries) {
            if (e.isRefunded()) continue;
            AppUser participant = e.getUser();
            participant.setCoins(participant.getCoins() + e.getEntryFeeExc());
            userService.save(participant);
            excTx.log(participant, e.getEntryFeeExc(), ExcTransactionService.TOURNAMENT,
                    "Возврат взноса (турнир отменён — недобор участников): " + t.getName());
            e.setRefunded(true);
            e.setRefundedAt(LocalDateTime.now());
            tournamentEntryRepository.save(e);
            refunded.add(e);
        }

        log.info("Tournament {} cancelled: {} entries < min {}. Refunded {} entries.",
                t.getId(), entries.size(), t.getMinParticipants(), refunded.size());
        eventPublisher.publishEvent(new TournamentCancelledEvent(this, t, refunded));
    }

    /** No blanket @Transactional — see activateRegistrationTournaments() for why. */
    public void settleFinishedTournaments() {
        List<Tournament> active = tournamentRepository.findAllByStatusOrderByCreatedAtDesc(Tournament.Status.ACTIVE);
        for (Tournament t : active) {
            if (t.getEndDate() != null && LocalDateTime.now().isAfter(t.getEndDate())) {
                if (t.getScoringType().isTrophyRace()) {
                    List<TournamentEntry> entries = tournamentEntryRepository.findAllWithUserByTournament(t);
                    trophyTournamentService.takeEndSnapshots(t, entries);
                }
                settle(t);
            }
        }
    }

    private void settle(Tournament tournament) {
        List<TournamentEntry> entries = tournamentEntryRepository.findAllWithUserByTournament(tournament);
        if (entries.isEmpty()) {
            tournament.setStatus(Tournament.Status.FINISHED);
            tournamentRepository.save(tournament);
            autoCreateNextTournament(tournament);
            return;
        }

        record EntryScore(TournamentEntry entry, long score) {}
        List<EntryScore> scored = new ArrayList<>();
        if (tournament.getScoringType().isTrophyRace()) {
            for (TournamentEntry e : entries) {
                Integer score = trophyTournamentService.computeScore(e);
                if (score != null) scored.add(new EntryScore(e, score));
            }
        } else {
            // Count quests approved during tournament window for each participant
            for (TournamentEntry e : entries) {
                long score = questSubmissionRepository.countApprovedByUserBetween(
                        e.getUser(), tournament.getStartDate(), tournament.getEndDate());
                scored.add(new EntryScore(e, score));
            }
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

            boolean payoutWithheld = entry.isAnomalyFlag() && !entry.isAnomalyResolved();
            if (payoutWithheld) {
                entry.setPayoutHeld(true);
            } else if (prize > 0) {
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
        autoCreateNextTournament(tournament);
    }

    /**
     * Клонирует завершённый турнир (те же name/gameName/entryFeeExc и длительность), чтобы всегда было
     * что-то активное для игроков — раньше турниры создавал только админ вручную, между циклами
     * бывали долгие паузы без активного турнира. Новый турнир открывается в REGISTRATION с реальным
     * окном на регистрацию (AUTO_CONTINUATION_REGISTRATION_WINDOW) — раньше startDate ставился в
     * LocalDateTime.now(), и турнир активировался на следующем тике планировщика (~60 сек), фактически
     * без единого шанса зарегистрироваться (найдено 2026-09-14 на живом Brawl Stars турнире).
     */
    private void autoCreateNextTournament(Tournament finished) {
        try {
            if (finished.getStartDate() == null || finished.getEndDate() == null) {
                log.warn("Tournament {} has no start/end dates, skipping auto-continuation", finished.getId());
                return;
            }
            Duration duration = Duration.between(finished.getStartDate(), finished.getEndDate());
            if (duration.isNegative() || duration.isZero()) {
                log.warn("Tournament {} has invalid duration, skipping auto-continuation", finished.getId());
                return;
            }
            LocalDateTime newStart = LocalDateTime.now().plus(AUTO_CONTINUATION_REGISTRATION_WINDOW);
            Tournament next = create(finished.getName(), finished.getDescription(), finished.getGameName(), finished.getEntryFeeExc(),
                    newStart, newStart.plus(duration), finished.getMinParticipants(), finished.getPhotoFileId());
            log.info("Auto-created continuation tournament {} (from finished {}), registration open until {}",
                    next.getId(), finished.getId(), newStart);
            // Авто-клон Clash Royale может неожиданно попасть на границу сезона - админа при создании вручную предупреждает
            // мастер, тут человека в цикле нет, поэтому сообщаем постфактум (турнир остаётся, решать админу).
            if (next.getScoringType() == Tournament.ScoringType.CLASH_ROYALE_TROPHIES) {
                ClashRoyaleSeasonGuard.check(LocalDateTime.now(), next.getStartDate(), next.getEndDate())
                        .ifPresent(w -> eventPublisher.publishEvent(new TournamentSeasonWarningEvent(this, next, w)));
            }
        } catch (Exception e) {
            log.error("Failed to auto-create continuation tournament after {}", finished.getId(), e);
        }
    }
}
