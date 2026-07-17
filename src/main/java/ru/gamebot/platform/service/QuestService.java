package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.enums.SubmissionStatus;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.model.QuestSubmission;
import ru.gamebot.platform.domain.model.Season;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.QuestRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuestService {

    private static final int WEEKLY_QUEST_TYPE_LIMIT = 3;
    private static final int COOLDOWN_HOURS = 24;
    private static final int HARD_COOLDOWN_HOURS = 336; // 14 дней для Сложных

    private static int cooldownHours(Quest quest) {
        return "Сложные".equals(quest.getCategory()) ? HARD_COOLDOWN_HOURS : COOLDOWN_HOURS;
    }
    private static final int REFERRAL_BONUS_PERCENT = 3;
    private static final int REFERRAL_DAYS_WINDOW = 14;

    // Жёсткий кулдаун на отправку отчёта убран по решению пользователя (2026-07-11) — throughput и так
    // ограничен часовым кулдауном на взятие, лимитом слотов, 24ч на повтор того же квеста и diminishing returns.
    // Вместо блокировки — мягкий антифрод-флаг: отчёт отправлен быстрее этого порога после взятия квеста.
    private static final int SUBMIT_FLAG_THRESHOLD_MINUTES = 30;

    // Минимальный срок квеста в днях — гарантирует, что у игрока в принципе есть окно на выполнение,
    // а не квест с дедлайном в тот же день.
    private static final int DURATION_BUFFER_DAYS = 1;

    private final QuestRepository questRepository;
    private final QuestSubmissionRepository questSubmissionRepository;
    private final AppUserRepository appUserRepository;
    private final UserService userService;
    private final HealthRatioService healthRatioService;
    private final SinkShopService sinkShopService;
    private final ExcTransactionService excTx;
    private final SeasonService seasonService;
    private final SponsorService sponsorService;
    private final ApplicationEventPublisher eventPublisher;

    public List<Quest> findActiveQuests() {
        return questRepository.findAllByActiveTrueOrderByCreatedAtDesc();
    }

    public List<Quest> findActiveSponsored() {
        return findActiveQuests().stream().filter(Quest::isSponsored).toList();
    }

    public List<Quest> findActiveUgc() {
        return findActiveQuests().stream()
                .filter(q -> !q.isSponsored() && "UGC".equalsIgnoreCase(q.getGameName()))
                .toList();
    }

    public List<Quest> findActiveQuestsForUser(boolean isCouncilMember, boolean hasSeasonPass) {
        return findActiveQuests().stream()
                .filter(q -> !q.isCouncilOnly() || isCouncilMember)
                .filter(q -> !q.isSeasonOnly() || hasSeasonPass)
                .toList();
    }

    public List<Quest> findActiveCouncilQuests() {
        return findActiveQuests().stream()
                .filter(Quest::isCouncilOnly)
                .toList();
    }

    public List<String> findActiveGameNames() {
        return findActiveQuests().stream()
                .filter(q -> !q.isSponsored() && !"UGC".equalsIgnoreCase(q.getGameName()))
                .map(Quest::getGameName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    public List<String> findAllGameNames() {
        return questRepository.findAll().stream()
                .filter(q -> !q.isSponsored() && !"UGC".equalsIgnoreCase(q.getGameName()))
                .map(Quest::getGameName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    public List<Quest> findByCategory(String category) {
        return findActiveQuests().stream()
                .filter(quest -> sameCategory(quest.getCategory(), category))
                .toList();
    }

    public List<Quest> findActiveByGameNameAndCategory(String gameName, String category) {
        return findActiveByGameName(gameName).stream()
                .filter(quest -> sameCategory(quest.getCategory(), category))
                .toList();
    }

    public List<Quest> findActiveByGameName(String gameName) {
        return findActiveQuests().stream()
                .filter(quest -> sameGame(quest.getGameName(), gameName))
                .filter(q -> !q.isSponsored())
                .sorted(Comparator.comparing(Quest::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public List<Quest> findAllByGameName(String gameName) {
        return questRepository.findAll().stream()
                .filter(quest -> sameGame(quest.getGameName(), gameName))
                .sorted(Comparator.comparing(Quest::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public List<Quest> findAllByGameNameAndCategory(String gameName, String category) {
        return findAllByGameName(gameName).stream()
                .filter(quest -> sameCategory(quest.getCategory(), category))
                .toList();
    }

    public List<Quest> findAll() {
        return questRepository.findAll();
    }

    public Quest getQuest(Long questId) {
        return questRepository.findById(questId)
                .orElseThrow(() -> new IllegalArgumentException("Квест не найден."));
    }

    @Transactional
    public Quest createQuest(Quest quest) {
        quest.setCreatedAt(LocalDateTime.now());
        quest.setActive(true);
        // Safety net: срок должен покрывать минимальный кулдаун сдачи отчёта для категории,
        // иначе дедлайн наступит раньше, чем откроется возможность отправить отчёт.
        int minDays = minDurationDaysForCategory(quest.getCategory());
        if (quest.getDurationDays() < minDays) {
            quest.setDurationDays(minDays);
            quest.setDurationText(minDays + (minDays == 1 ? " день" : minDays < 5 ? " дня" : " дней"));
        }
        return questRepository.save(quest);
    }

    @Transactional
    public Quest save(Quest quest) {
        return questRepository.save(quest);
    }

    /**
     * Минимальный срок квеста в днях. Раньше зависел от категории (кулдаун сдачи отчёта в часах),
     * но с переходом на мягкий антифрод-флаг вместо блокировки стал единым для всех категорий.
     */
    public int minDurationDaysForCategory(String category) {
        return DURATION_BUFFER_DAYS;
    }

    /**
     * При смене категории квеста продлевает срок (и все активные заявки по нему) так,
     * чтобы дедлайн не наступал раньше минимального кулдауна сдачи отчёта новой категории.
     * Возвращает новый срок в днях, если было продление, иначе 0.
     */
    @Transactional
    public int ensureDurationCoversCategory(Quest quest, String newCategory) {
        int minDays = minDurationDaysForCategory(newCategory);
        if (quest.getDurationDays() >= minDays) {
            return 0;
        }
        int extraDays = minDays - quest.getDurationDays();
        quest.setDurationDays(minDays);
        quest.setDurationText(minDays + (minDays == 1 ? " день" : minDays < 5 ? " дня" : " дней"));
        questRepository.save(quest);

        for (QuestSubmission submission : questSubmissionRepository.findActiveByQuest(quest)) {
            if (submission.getExpiresAt() != null) {
                submission.setExpiresAt(submission.getExpiresAt().plusDays(extraDays));
                questSubmissionRepository.save(submission);
            }
        }
        return minDays;
    }

    public QuestSubmission getLatestSubmission(AppUser user, Quest quest) {
        return questSubmissionRepository.findTopByUserAndQuestOrderByCreatedAtDesc(user, quest).orElse(null);
    }

    public boolean isSameQuestCooldownActive(AppUser user, Quest quest) {
        Optional<LocalDateTime> lastApproved = questSubmissionRepository
                .findLastApprovedDateByUserAndQuest(user, quest);
        if (lastApproved.isPresent() && LocalDateTime.now().isBefore(lastApproved.get().plusHours(cooldownHours(quest)))) {
            return true;
        }
        // Fix 5: cancelled submissions also block retake for 1h to prevent cancel-retake abuse
        Optional<QuestSubmission> lastCancelled = questSubmissionRepository
                .findTopByUserAndQuestOrderByCreatedAtDesc(user, quest);
        if (lastCancelled.isPresent() && lastCancelled.get().getStatus() == SubmissionStatus.CANCELLED) {
            LocalDateTime cancelledAt = lastCancelled.get().getUpdatedAt();
            if (cancelledAt != null && LocalDateTime.now().isBefore(cancelledAt.plusHours(1))) {
                return true;
            }
        }
        return false;
    }

    public boolean isCooldownActive(AppUser user, Quest quest) {
        Optional<LocalDateTime> lastApproved = questSubmissionRepository
                .findLastApprovedDateByUserAndGameAndCategory(user, quest.getGameName(), quest.getCategory());
        if (lastApproved.isPresent() && LocalDateTime.now().isBefore(lastApproved.get().plusHours(cooldownHours(quest)))) {
            if (sinkShopService.hasCooldownBypass(user, quest.getGameName())) {
                sinkShopService.consumeCooldownBypass(user, quest.getGameName());
                return false;
            }
            return true;
        }
        return false;
    }

    /** Возвращает сколько часов осталось до снятия кулдауна (0 = нет кулдауна) */
    public long getCooldownHoursLeft(AppUser user, Quest quest) {
        Optional<LocalDateTime> lastApproved = questSubmissionRepository
                .findLastApprovedDateByUserAndQuest(user, quest);
        int cd = cooldownHours(quest);
        if (lastApproved.isPresent()) {
            LocalDateTime until = lastApproved.get().plusHours(cd);
            if (LocalDateTime.now().isBefore(until)) {
                return Math.max(1, java.time.temporal.ChronoUnit.HOURS.between(LocalDateTime.now(), until));
            }
        }
        Optional<LocalDateTime> lastGame = questSubmissionRepository
                .findLastApprovedDateByUserAndGameAndCategory(user, quest.getGameName(), quest.getCategory());
        if (lastGame.isPresent()) {
            LocalDateTime until = lastGame.get().plusHours(cd);
            if (LocalDateTime.now().isBefore(until)) {
                return Math.max(1, java.time.temporal.ChronoUnit.HOURS.between(LocalDateTime.now(), until));
            }
        }
        return 0;
    }

    public long getWeeklyCompletionsOfType(AppUser user, Quest quest) {
        return questSubmissionRepository.countApprovedByUserAndGameAndCategorySince(
                user, quest.getGameName(), quest.getCategory(), LocalDateTime.now().minusWeeks(1));
    }

    @Transactional
    public QuestSubmission resetToDraft(QuestSubmission submission) {
        submission.setStatus(SubmissionStatus.DRAFT);
        submission.setMediaType(null);
        submission.setMediaFileId(null);
        submission.setExternalLink(null);
        submission.setUserComment(null);
        submission.setModeratorComment(null);
        submission.setUpdatedAt(LocalDateTime.now());
        return questSubmissionRepository.save(submission);
    }

    @Transactional
    public QuestSubmission createDraftSubmission(AppUser user, Quest quest) {
        // Fix 1: enforce participant limit
        if (quest.getParticipantLimit() != null && quest.getParticipantLimit() > 0) {
            long approved = questSubmissionRepository.countApprovedByQuest(quest);
            if (approved >= quest.getParticipantLimit()) {
                throw new IllegalArgumentException("Квест закрыт — набор участников завершён (" + quest.getParticipantLimit() + "/" + quest.getParticipantLimit() + ").");
            }
        }
        QuestSubmission submission = new QuestSubmission();
        submission.setUser(user);
        submission.setQuest(quest);
        submission.setStatus(SubmissionStatus.DRAFT);
        submission.setCreatedAt(LocalDateTime.now());
        submission.setUpdatedAt(LocalDateTime.now());
        submission.setDisplayId(questSubmissionRepository.findMaxDisplayId() + 1);
        if (quest.getDurationDays() > 0) {
            submission.setExpiresAt(LocalDateTime.now().plusDays(quest.getDurationDays()));
        }
        return questSubmissionRepository.save(submission);
    }

    public boolean isExpired(QuestSubmission submission) {
        return submission.getExpiresAt() != null
                && LocalDateTime.now().isAfter(submission.getExpiresAt());
    }

    public record QuestActionResult(QuestActionStatus status, long minutesLeft, QuestSubmission submission) {
        public static QuestActionResult ok(QuestSubmission s) {
            return new QuestActionResult(QuestActionStatus.OK, 0, s);
        }
        public static QuestActionResult of(QuestActionStatus status, long minutesLeft) {
            return new QuestActionResult(status, minutesLeft, null);
        }
    }

    /**
     * Единая точка входа для взятия квеста — используется и ботом, и API Mini App.
     * Переиспользует те же правила: слот-лимит, кулдаун между взятием (1ч), кулдаун по игре/категории (24ч), 24ч на повтор того же квеста.
     *
     * Строка пользователя блокируется на всё время проверок ({@link AppUserRepository#findByIdForUpdate}) —
     * без этого два конкурентных запроса (двойное нажатие «Взять квест», гонка между ботом и Mini App)
     * могли пройти проверку лимитов одновременно, до того как первый из них успеет записать результат.
     * Реальный случай: игрок взял 4 квеста за 29 минут при лимите «1 квест в час, 1 слот».
     */
    @Transactional
    public QuestActionResult takeQuestChecked(AppUser user, Quest quest) {
        AppUser lockedUser = appUserRepository.findByIdForUpdate(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден."));

        QuestSubmission latest = getLatestSubmission(lockedUser, quest);
        if (latest != null) {
            if (latest.getStatus() == SubmissionStatus.DRAFT) {
                return QuestActionResult.of(QuestActionStatus.ALREADY_DRAFT, 0);
            }
            if (latest.getStatus() == SubmissionStatus.PENDING) {
                return QuestActionResult.of(QuestActionStatus.ALREADY_PENDING, 0);
            }
            if (latest.getStatus() == SubmissionStatus.APPROVED) {
                return QuestActionResult.of(QuestActionStatus.ALREADY_APPROVED, 0);
            }
            if (latest.getStatus() == SubmissionStatus.REJECTED || latest.getStatus() == SubmissionStatus.NEEDS_INFO) {
                return QuestActionResult.of(QuestActionStatus.HAS_REJECTED_REPORT, 0);
            }
        }

        long activeSlots = countActiveDrafts(lockedUser);
        long maxSlots = sinkShopService.getMaxQuestSlots(lockedUser);
        if (activeSlots >= maxSlots) {
            return QuestActionResult.of(QuestActionStatus.SLOTS_FULL, 0);
        }

        if (isSameQuestCooldownActive(lockedUser, quest)) {
            return QuestActionResult.of(QuestActionStatus.SAME_QUEST_COOLDOWN, cooldownHours(quest) * 60L);
        }

        if (isCooldownActive(lockedUser, quest)) {
            long hoursLeft = getCooldownHoursLeft(lockedUser, quest);
            return QuestActionResult.of(QuestActionStatus.GAME_COOLDOWN, hoursLeft * 60L);
        }

        if (lockedUser.getLastQuestTakenAt() != null) {
            long minutesSince = ChronoUnit.MINUTES.between(lockedUser.getLastQuestTakenAt(), LocalDateTime.now());
            if (minutesSince < 60) {
                return QuestActionResult.of(QuestActionStatus.TAKE_COOLDOWN, 60 - minutesSince);
            }
        }

        lockedUser.setLastQuestTakenAt(LocalDateTime.now());
        appUserRepository.save(lockedUser);
        QuestSubmission created = createDraftSubmission(lockedUser, quest);
        return QuestActionResult.ok(created);
    }

    /**
     * Единая точка входа для отправки отчёта — используется и ботом, и API Mini App.
     * Переиспользует те же правила: кулдаун после отклонения (1ч), дедлайн квеста, не более одного
     * отчёта на проверке одновременно (по всем квестам сразу, не только по этому). Отчёт можно
     * отправить сразу после взятия квеста — слишком быстрая отправка не блокируется, а помечается
     * антифрод-флагом в {@link #submitReport}.
     *
     * Строка пользователя блокируется на всё время проверок ({@link AppUserRepository#findByIdForUpdate}) —
     * та же защита от гонки состояний, что и в {@link #takeQuestChecked}.
     */
    @Transactional
    public QuestActionResult submitReportChecked(AppUser user, Quest quest, String mediaType, String fileId,
                                                  String externalLink, String comment) {
        AppUser lockedUser = appUserRepository.findByIdForUpdate(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден."));

        QuestSubmission latest = getLatestSubmission(lockedUser, quest);
        if (latest == null || latest.getStatus() == SubmissionStatus.CANCELLED) {
            return QuestActionResult.of(QuestActionStatus.NOT_TAKEN, 0);
        }
        if (latest.getStatus() == SubmissionStatus.PENDING) {
            return QuestActionResult.of(QuestActionStatus.ALREADY_PENDING, 0);
        }
        if (latest.getStatus() == SubmissionStatus.APPROVED) {
            return QuestActionResult.of(QuestActionStatus.ALREADY_APPROVED, 0);
        }
        if (latest.getStatus() == SubmissionStatus.REJECTED || latest.getStatus() == SubmissionStatus.NEEDS_INFO) {
            LocalDateTime rejectedAt = latest.getUpdatedAt();
            if (rejectedAt != null && LocalDateTime.now().isBefore(rejectedAt.plusHours(1))) {
                long minutesLeft = ChronoUnit.MINUTES.between(LocalDateTime.now(), rejectedAt.plusHours(1));
                return QuestActionResult.of(QuestActionStatus.REJECT_COOLDOWN, Math.max(1, minutesLeft));
            }
            latest = resetToDraft(latest);
        }

        if (isExpired(latest)) {
            return QuestActionResult.of(QuestActionStatus.EXPIRED, 0);
        }

        if (hasOtherPendingSubmission(lockedUser, quest)) {
            return QuestActionResult.of(QuestActionStatus.HAS_PENDING_REPORT, 0);
        }

        QuestSubmission submitted = submitReport(latest, mediaType, fileId, externalLink, comment);
        // Уведомление модераторов — только этот путь (submitReportChecked) используется Mini App;
        // сам бот сдаёт отчёт через submitReport() напрямую и уведомляет модераторов сам, отдельно от этого события.
        eventPublisher.publishEvent(new ru.gamebot.platform.event.QuestReportSubmittedEvent(this, submitted.getId()));
        return QuestActionResult.ok(submitted);
    }

    /** true, если у пользователя уже есть ДРУГОЙ отчёт на проверке (статус PENDING) по любому квесту. */
    public boolean hasOtherPendingSubmission(AppUser user, Quest excludeQuest) {
        return questSubmissionRepository.findAllByUserOrderByCreatedAtDesc(user).stream()
                .anyMatch(s -> s.getStatus() == SubmissionStatus.PENDING
                        && !s.getQuest().getId().equals(excludeQuest.getId()));
    }

    @Transactional
    public QuestSubmission submitReport(QuestSubmission submission, String mediaType, String fileId,
                                        String externalLink, String comment) {
        if (submission.getStatus() == SubmissionStatus.APPROVED) {
            throw new IllegalStateException("Этот квест уже одобрен и оплачен — повторная сдача отчёта невозможна.");
        }
        // Защита от гонки/обхода: те же правила, что и в submitReportChecked, продублированы здесь,
        // т.к. бот исторически вызывал submitReport напрямую в некоторых местах, минуя проверки.
        if (hasOtherPendingSubmission(submission.getUser(), submission.getQuest())) {
            throw new IllegalStateException("pending_report_exists");
        }
        submission.setMediaType(mediaType);
        submission.setMediaFileId(fileId);
        submission.setExternalLink(externalLink);
        submission.setUserComment(comment);
        submission.setStatus(SubmissionStatus.PENDING);
        submission.setUpdatedAt(LocalDateTime.now());
        questSubmissionRepository.save(submission);

        flagIfFastSubmit(submission);
        checkFraudSuspect(submission.getUser());

        return submission;
    }

    /** Мягкий антифрод-сигнал вместо блокировки: отчёт отправлен подозрительно быстро после взятия квеста. */
    private void flagIfFastSubmit(QuestSubmission submission) {
        AppUser user = submission.getUser();
        if (user.isFraudSuspect()) {
            return;
        }
        long minutesSinceTaken = ChronoUnit.MINUTES.between(submission.getCreatedAt(), LocalDateTime.now());
        if (minutesSinceTaken < SUBMIT_FLAG_THRESHOLD_MINUTES) {
            user.setFraudSuspect(true);
            appUserRepository.save(user);
            log.warn("Fraud suspect flagged: userId={} submitted report {} min after taking quest {}",
                    user.getTelegramId(), minutesSinceTaken, submission.getQuest().getId());
        }
    }

    private void checkFraudSuspect(AppUser user) {
        if (user.isFraudSuspect()) {
            return;
        }

        // Check success rate > 90%
        long totalReviewed = questSubmissionRepository.countReviewedByUser(user);
        if (totalReviewed >= 5) {
            long totalApproved = questSubmissionRepository.countApprovedByUser(user);
            double successRate = (double) totalApproved / totalReviewed;
            if (successRate > 0.9) {
                // Check interval < 10 seconds between recent pending submissions
                List<LocalDateTime> recentTimes = questSubmissionRepository.findRecentPendingSubmissionTimes(user);
                if (recentTimes.size() >= 2) {
                    long secondsBetween = java.time.temporal.ChronoUnit.SECONDS.between(
                            recentTimes.get(1), recentTimes.get(0));
                    if (secondsBetween < 60) {
                        user.setFraudSuspect(true);
                        appUserRepository.save(user);
                        log.warn("Fraud suspect flagged: userId={} successRate={} interval={}s",
                                user.getTelegramId(), successRate, secondsBetween);
                    }
                }
            }
        }
    }

    public QuestSubmission getSubmission(Long submissionId) {
        return questSubmissionRepository.findWithUserAndQuestById(submissionId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена."));
    }

    public List<QuestSubmission> getPendingSubmissions() {
        return questSubmissionRepository.findAllByStatusOrderByCreatedAtAsc(SubmissionStatus.PENDING);
    }

    public List<QuestSubmission> getUserSubmissions(AppUser user) {
        return questSubmissionRepository.findAllByUserOrderByCreatedAtDesc(user).stream()
                .filter(s -> s.getStatus() != SubmissionStatus.CANCELLED)
                .toList();
    }

    public record RewardPreview(long xp, long coins, boolean diminished, boolean xpBoosted) {
    }

    public RewardPreview computeReward(AppUser user, Quest quest) {
        long baseCoins = quest.getRewardCoins();
        long adjustedCoins = baseCoins; // EXC начисляются полные; HR влияет только на рублёвый эквивалент

        // 3.4 Antifaud: diminishing returns after 3 completions of same type per week
        LocalDateTime weekAgo = LocalDateTime.now().minusWeeks(1);
        long weeklyCount = questSubmissionRepository.countApprovedByUserAndGameAndCategorySince(
                user, quest.getGameName(), quest.getCategory(), weekAgo);
        boolean diminished = weeklyCount >= WEEKLY_QUEST_TYPE_LIMIT;
        if (diminished) {
            adjustedCoins = adjustedCoins / 2;
        }

        // Apply XP boost
        long baseXp = quest.getRewardXp();
        int xpBoostPct = sinkShopService.getXpBoostPercent(user);
        // Season Pass adds extra XP boost
        if (seasonService.hasActivePass(user)) {
            int seasonBoost = seasonService.findCurrentSeason()
                    .map(Season -> Season.getXpBoostPercent()).orElse(0);
            xpBoostPct += seasonBoost;
        }
        long adjustedXp = baseXp + (baseXp * xpBoostPct / 100);

        return new RewardPreview(adjustedXp, adjustedCoins, diminished, xpBoostPct > 0);
    }

    @Transactional
    public QuestSubmission approveSubmission(Long submissionId) {
        QuestSubmission submission = getSubmission(submissionId);
        if (submission.getStatus() == SubmissionStatus.APPROVED) {
            return submission;
        }
        submission.setStatus(SubmissionStatus.APPROVED);
        submission.setModeratorComment("Принято. Отличная работа!");
        submission.setUpdatedAt(LocalDateTime.now());
        submission.setCompletionDisplayId(questSubmissionRepository.findMaxCompletionDisplayId() + 1);

        AppUser user = submission.getUser();
        Quest quest = submission.getQuest();

        RewardPreview reward = computeReward(user, quest);
        long adjustedCoins = reward.coins();
        long adjustedXp = reward.xp();

        // Фиксируем рублёвый эквивалент по HR на момент одобрения
        double currentHR = healthRatioService.getCurrentRatio();
        long fixedRub = Math.round(adjustedCoins * currentHR / 100.0);
        submission.setFixedRubValue(fixedRub);

        // 3.5 3000 EXC bonus on first quest (before completedQuests increment)
        userService.grantFirstQuestReferralBonus(user);

        userService.addReward(user, adjustedXp, adjustedCoins);
        excTx.log(user, adjustedCoins, ExcTransactionService.QUEST,
                quest.getTitle() + " (" + quest.getGameName() + ")");
        user.setCompletedQuests(user.getCompletedQuests() + 1);
        user.setFixedRubBalance(user.getFixedRubBalance() + fixedRub);
        submission.setUser(user);
        questSubmissionRepository.save(submission);

        // Track sponsored quest spend
        if (quest.isSponsored() && quest.getSponsorId() != null) {
            sponsorService.recordSpend(quest.getSponsorId(), adjustedCoins);
        }

        // 3.5 Referral bonus: 10% of EXC earned by referred in first 30 days
        grantReferralBonus(user, adjustedCoins);

        return submission;
    }

    private void grantReferralBonus(AppUser invitedUser, long earnedCoins) {
        Long referrerTelegramId = invitedUser.getReferredByTelegramId();
        if (referrerTelegramId == null) {
            return;
        }
        // Fix 3: prevent self-referral (multi-account farming)
        if (referrerTelegramId.equals(invitedUser.getTelegramId())) {
            return;
        }
        if (invitedUser.getCreatedAt() == null) {
            return;
        }
        long daysSinceJoin = ChronoUnit.DAYS.between(invitedUser.getCreatedAt(), LocalDateTime.now());
        // Fix 10: off-by-one — was >, should be >= to correctly close window on day 14
        if (daysSinceJoin >= REFERRAL_DAYS_WINDOW) {
            return;
        }
        AppUser referrer = appUserRepository.findByTelegramId(referrerTelegramId).orElse(null);
        if (referrer == null) {
            return;
        }
        long bonus = Math.max(1, earnedCoins * REFERRAL_BONUS_PERCENT / 100);
        userService.addReward(referrer, 0, bonus);
        excTx.log(referrer, bonus, ExcTransactionService.REFERRAL,
                "3% с квеста реферала " + invitedUser.getNickname());
        referrer.setReferralEarnedExc(referrer.getReferralEarnedExc() + bonus);
        appUserRepository.save(referrer);
    }

    @Transactional
    public QuestSubmission rejectSubmission(Long submissionId, String moderatorComment) {
        QuestSubmission submission = getSubmission(submissionId);
        submission.setStatus(SubmissionStatus.REJECTED);
        submission.setModeratorComment(moderatorComment);
        submission.setUpdatedAt(LocalDateTime.now());
        return questSubmissionRepository.save(submission);
    }

    @Transactional
    public QuestSubmission requestClarification(Long submissionId) {
        QuestSubmission submission = getSubmission(submissionId);
        submission.setStatus(SubmissionStatus.NEEDS_INFO);
        submission.setModeratorComment("Нужны уточнения. Пожалуйста, отправьте более понятный отчёт.");
        submission.setUpdatedAt(LocalDateTime.now());
        return questSubmissionRepository.save(submission);
    }

    @Transactional
    public QuestSubmission cancelSubmission(Long submissionId, AppUser user) {
        QuestSubmission submission = getSubmission(submissionId);
        if (!submission.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Нет доступа.");
        }
        if (submission.getStatus() == SubmissionStatus.APPROVED) {
            throw new IllegalArgumentException("Одобренный квест нельзя отменить.");
        }
        if (submission.getStatus() == SubmissionStatus.CANCELLED) {
            throw new IllegalArgumentException("Квест уже отменён.");
        }
        submission.setStatus(SubmissionStatus.CANCELLED);
        submission.setUpdatedAt(LocalDateTime.now());
        return questSubmissionRepository.save(submission);
    }

    @Transactional
    public int resetActiveSubmissions(AppUser user) {
        List<QuestSubmission> active = questSubmissionRepository.findAllByUserOrderByCreatedAtDesc(user)
                .stream()
                .filter(s -> s.getStatus() == SubmissionStatus.DRAFT || s.getStatus() == SubmissionStatus.PENDING)
                .toList();
        for (QuestSubmission s : active) {
            s.setStatus(SubmissionStatus.CANCELLED);
            s.setUpdatedAt(LocalDateTime.now());
            questSubmissionRepository.save(s);
        }
        return active.size();
    }

    public long countReviewedByUser(AppUser user) {
        return questSubmissionRepository.countReviewedByUser(user);
    }

    public long countApprovedByUser(AppUser user) {
        return questSubmissionRepository.countApprovedByUser(user);
    }

    public List<QuestSubmission> findApprovedByUser(AppUser user) {
        return questSubmissionRepository.findAllApprovedByUserOrderByUpdatedAtDesc(user);
    }

    public List<QuestSubmission> findAllByUser(AppUser user) {
        return questSubmissionRepository.findAllByUserOrderByCreatedAtDesc(user);
    }

    public long countAllApproved() {
        return questSubmissionRepository.countAllApproved();
    }

    public long sumAllIssuedCoins() {
        return questSubmissionRepository.sumAllIssuedCoins();
    }

    public String topGameName() {
        List<String> names = questSubmissionRepository.findTopGameNames();
        return names.isEmpty() ? "—" : names.get(0);
    }

    public long pendingCount() {
        return questSubmissionRepository.countByStatus(SubmissionStatus.PENDING);
    }

    /** Квесты, которые игроки прямо сейчас выполняют: взяты в работу или на проверке, срок ещё не истёк. */
    public long countActiveInProgress() {
        return questSubmissionRepository.countActiveInProgress();
    }

    public List<Object[]> getTopQuestsByCompletions() {
        return questSubmissionRepository.findTopQuestsByCompletions();
    }

    @Transactional
    public long deleteQuest(Long questId) {
        Quest quest = getQuest(questId);
        long submissions = questSubmissionRepository.countByQuest(quest);
        if (submissions > 0) {
            questSubmissionRepository.deleteAllByQuest(quest);
        }
        questRepository.delete(quest);
        return submissions;
    }

    public long countActiveDrafts(AppUser user) {
        return questSubmissionRepository.findAllByUserOrderByCreatedAtDesc(user).stream()
                .filter(s -> (s.getStatus() == SubmissionStatus.DRAFT || s.getStatus() == SubmissionStatus.PENDING) && !isExpired(s))
                .count();
    }

    private boolean sameGame(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.trim().equalsIgnoreCase(right.trim());
    }

    private boolean sameCategory(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return normalizeCategory(left).equalsIgnoreCase(normalizeCategory(right));
    }

    private String normalizeCategory(String value) {
        String normalized = value == null ? "" : value.trim();
        return switch (normalized.toLowerCase()) {
            case "быстрые", "легкие", "лёгкие", "лёгкие задания", "легкие задания" -> "Лёгкие";
            case "долгие", "сложные", "сложные задания" -> "Сложные";
            case "средние", "средние задания" -> "Средние";
            default -> normalized;
        };
    }
}
