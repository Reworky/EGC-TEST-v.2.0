package ru.gamebot.platform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.gamebot.platform.domain.enums.SubmissionStatus;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Poll;
import ru.gamebot.platform.domain.model.QuestSubmission;
import ru.gamebot.platform.domain.repository.QuestRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;
import ru.gamebot.platform.domain.repository.WheelSpinLogRepository;
import ru.gamebot.platform.event.CooldownExpiredEvent;
import ru.gamebot.platform.event.DormancyReengagementEvent;
import ru.gamebot.platform.event.OnboardingReminderEvent;
import ru.gamebot.platform.event.PollClosedEvent;
import ru.gamebot.platform.event.QuestDeadlineWarningEvent;
import ru.gamebot.platform.event.QuestExpiredEvent;
import ru.gamebot.platform.event.WeeklyReviewSummaryEvent;
import ru.gamebot.platform.event.SquadMidweekTeaserEvent;
import ru.gamebot.platform.event.StreakAtRiskEvent;
import ru.gamebot.platform.event.WeeklyDigestActiveEvent;
import ru.gamebot.platform.event.WeeklyDigestInactiveEvent;
import ru.gamebot.platform.service.TournamentService;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ru.gamebot.platform.domain.repository.AppUserRepository;

@Slf4j
@Component
@RequiredArgsConstructor
public class WeeklyResetScheduler {

    private final UserService userService;
    private final HealthRatioService healthRatioService;
    private final PollService pollService;
    private final TournamentService tournamentService;
    private final SquadService squadService;
    private final QuestSubmissionRepository questSubmissionRepository;
    private final QuestRepository questRepository;
    private final WheelSpinLogRepository wheelSpinLogRepository;
    private final WheelService wheelService;
    private final QuestRewardBoostService questRewardBoostService;
    private final ru.gamebot.platform.domain.repository.BotReviewRepository botReviewRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final PlatformSnapshotService platformSnapshotService;
    private final NewsService newsService;
    private final AppUserRepository appUserRepository;
    private final SeasonService seasonService;
    private final ExcTransactionService excTx;
    private final BrawlQuestVerificationService brawlQuestVerificationService;
    private final ClashQuestVerificationService clashQuestVerificationService;
    private final ClashRoyaleQuestVerificationService clashRoyaleQuestVerificationService;
    private final Dota2QuestVerificationService dota2QuestVerificationService;
    private final Cs2QuestVerificationService cs2QuestVerificationService;
    private final PubgQuestVerificationService pubgQuestVerificationService;
    private final ScheduledBroadcastService scheduledBroadcastService;
    private final AchievementCheckService achievementCheckService;

    private static final int[] DORMANCY_TIER_DAYS = {14, 30, 60};
    private static final long[] DORMANCY_TIER_EXC = {300, 750, 1500};

    @Scheduled(cron = "0 0 0 * * MON")
    public void resetWeeklyLeaderboard() {
        try {
            squadService.rewardTopSquad();
        } catch (Exception e) {
            log.error("Squad weekly reward failed — continuing with XP reset", e);
        }
        try {
            squadService.resetWeeklyBonusPoints();
        } catch (Exception e) {
            log.error("Squad weekly bonus points reset failed", e);
        }
        try {
            LocalDateTime weekEnd = LocalDate.now().with(DayOfWeek.MONDAY).atStartOfDay();
            LocalDateTime weekStart = weekEnd.minusWeeks(1);
            userService.rewardTopReferrers(weekStart, weekEnd);
        } catch (Exception e) {
            log.error("Referral weekly leaderboard reward failed — continuing with XP reset", e);
        }
        try {
            userService.resetWeeklyXp();
            log.info("Weekly XP has been reset.");
        } catch (Exception e) {
            log.error("Weekly XP reset failed", e);
        }
        try {
            healthRatioService.recalculate();
        } catch (Exception e) {
            log.error("Health ratio recalculate after weekly reset failed", e);
        }
    }

    // Тизер рейтинга отрядов в середине недели — держит канал живым между "Залом Славы"
    // по понедельникам и выплатой приза; время 12:00, а не полночь, чтобы попасть в активные часы.
    @Scheduled(cron = "0 0 12 * * WED")
    public void postSquadMidweekTeaser() {
        try {
            List<SquadService.SquadRankEntry> top = squadService.getLeaderboard().stream().limit(5).toList();
            if (top.isEmpty()) return;
            eventPublisher.publishEvent(new SquadMidweekTeaserEvent(this, top));
        } catch (Exception e) {
            log.error("Squad midweek teaser failed", e);
        }
    }

    // Раз в неделю, утро понедельника — предложить админу обобщённый отчёт по отзывам за прошлую
    // неделю для репоста в основной канал как соцдоказательство. Заменяет репост одного отдельного
    // отзыва (по явному запросу 2026-09-14: "не конкретный отзыв, а один обобщённый за неделю") —
    // приурочено к тому, что админ сам готовит недельный итоговый отчёт по понедельникам.
    @Scheduled(cron = "0 0 9 * * MON")
    public void postWeeklyReviewSummary() {
        try {
            LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
            List<ru.gamebot.platform.domain.model.BotReview> weekReviews =
                    botReviewRepository.findAllByStatusAndCreatedAtAfterOrderByStarsDescCreatedAtDesc(
                            ru.gamebot.platform.domain.enums.BotReviewStatus.PUBLISHED, weekAgo);
            if (weekReviews.isEmpty()) return;
            eventPublisher.publishEvent(new WeeklyReviewSummaryEvent(this,
                    weekReviews.stream().map(ru.gamebot.platform.domain.model.BotReview::getId).toList()));
        } catch (Exception e) {
            log.error("Weekly review summary failed", e);
        }
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void recalculateHealthRatio() {
        healthRatioService.recalculate();
    }

    @Scheduled(fixedDelay = 60_000)
    public void closeExpiredPolls() {
        List<Poll> expired = pollService.findExpiredUnclosed();
        for (Poll poll : expired) {
            pollService.close(poll);
            eventPublisher.publishEvent(new PollClosedEvent(this, poll));
            log.info("Poll {} closed and results published.", poll.getId());
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void processTournaments() {
        tournamentService.activateRegistrationTournaments();
        tournamentService.settleFinishedTournaments();
    }

    // Проверяем каждые 5 минут, у кого истёк 24ч кулдаун (единый для всех неспонсорских квестов,
    // см. QuestService.cooldownHours — отдельная 336ч-ветка для «Сложные» убрана 2026-09-17)
    @Scheduled(fixedDelay = 300_000)
    public void notifyCooldownExpired() {
        LocalDateTime now = LocalDateTime.now();
        notifyExpired(questSubmissionRepository.findUsersWhoseNormalQuestCooldownExpiredBetween(
                now.minusHours(24).minusMinutes(5), now.minusHours(24)));
    }

    // Автоотмена просроченных заявок — каждый час
    @Scheduled(fixedDelay = 3_600_000)
    public void cancelExpiredSubmissions() {
        List<QuestSubmission> expired = questSubmissionRepository.findExpiredActive();
        for (QuestSubmission s : expired) {
            try {
                s.setStatus(SubmissionStatus.CANCELLED);
                s.setUpdatedAt(LocalDateTime.now());
                questSubmissionRepository.save(s);
                eventPublisher.publishEvent(new QuestExpiredEvent(this,
                        s.getUser().getTelegramId(), s.getQuest().getTitle()));
            } catch (Exception e) {
                log.warn("Failed to auto-cancel expired submission {}", s.getId(), e);
            }
        }
        if (!expired.isEmpty()) {
            log.info("Auto-cancelled {} expired quest submission(s)", expired.size());
        }
    }

    // Проверка прогресса авто-верификации квестов Brawl Stars — каждые 2 минуты (было 10, см. ниже).
    // Официальный battlelog-эндпоинт отдаёт только ПОСЛЕДНИЕ ~25 боёв без пагинации (см. javadoc
    // BrawlStarsApiService.fetchBattleLog) — это общий лог аккаунта, туда попадают ВСЕ бои игрока,
    // не только подходящие под квест. Активный игрок легко успевает сыграть 25+ боёв между опросами
    // раз в 10 минут — тогда старые бои вытесняются из лога раньше, чем курсор успевает их увидеть,
    // и прогресс квеста молча теряется без следа (жалоба игрока 2026-09-17: "нужно 5 матчей, играю
    // 20 — не засчитывается"). Более частый опрос не убирает проблему полностью (риск есть при любом
    // интервале > 0), но резко сокращает окно, в которое это может произойти.
    @Scheduled(fixedDelay = 120_000)
    public void checkBrawlAutoVerifyProgress() {
        try {
            brawlQuestVerificationService.checkInProgressSubmissions();
        } catch (Exception e) {
            log.error("Brawl auto-verify check failed", e);
        }
    }

    // Проверка прогресса авто-верификации квестов Clash of Clans — каждые 10 минут
    @Scheduled(fixedDelay = 600_000)
    public void checkClashAutoVerifyProgress() {
        try {
            clashQuestVerificationService.checkInProgressSubmissions();
        } catch (Exception e) {
            log.error("Clash auto-verify check failed", e);
        }
    }

    // Проверка прогресса авто-верификации квестов Clash Royale — каждые 10 минут
    @Scheduled(fixedDelay = 600_000)
    public void checkClashRoyaleAutoVerifyProgress() {
        try {
            clashRoyaleQuestVerificationService.checkInProgressSubmissions();
        } catch (Exception e) {
            log.error("Clash Royale auto-verify check failed", e);
        }
    }

    // Проверка прогресса авто-верификации квестов Dota 2 (Steam Web API) — каждые 10 минут
    @Scheduled(fixedDelay = 600_000)
    public void checkDotaAutoVerifyProgress() {
        try {
            dota2QuestVerificationService.checkInProgressSubmissions();
        } catch (Exception e) {
            log.error("Dota auto-verify check failed", e);
        }
    }

    // Проверка прогресса авто-верификации квестов CS2 (Steam Web API) — каждые 10 минут
    @Scheduled(fixedDelay = 600_000)
    public void checkCs2AutoVerifyProgress() {
        try {
            cs2QuestVerificationService.checkInProgressSubmissions();
        } catch (Exception e) {
            log.error("CS2 auto-verify check failed", e);
        }
    }

    // Проверка прогресса авто-верификации квестов PUBG PC (официальный API developer.pubg.com) — каждые 10 минут
    @Scheduled(fixedDelay = 600_000)
    public void checkPubgAutoVerifyProgress() {
        try {
            pubgQuestVerificationService.checkInProgressSubmissions();
        } catch (Exception e) {
            log.error("PUBG auto-verify check failed", e);
        }
    }

    // Проверка достижений (новый XP-уровень / круглая сумма EXC) — каждые 10 минут
    @Scheduled(fixedDelay = 600_000)
    public void checkAchievementMilestones() {
        try {
            achievementCheckService.checkMilestones();
        } catch (Exception e) {
            log.error("Achievement milestone check failed", e);
        }
    }

    // Рассылки, запланированные админом на конкретное время — проверяем раз в минуту
    @Scheduled(fixedDelay = 60_000)
    public void checkScheduledBroadcasts() {
        try {
            scheduledBroadcastService.checkDue();
        } catch (Exception e) {
            log.error("Scheduled broadcast check failed", e);
        }
    }

    // Предупреждение о дедлайне за 2 часа — каждые 10 минут
    @Scheduled(fixedDelay = 600_000)
    public void warnApproachingDeadlines() {
        LocalDateTime twoHoursFromNow = LocalDateTime.now().plusMinutes(120);
        List<QuestSubmission> expiring = questSubmissionRepository.findExpiringBefore(twoHoursFromNow);
        for (QuestSubmission s : expiring) {
            try {
                long minutesLeft = ChronoUnit.MINUTES.between(LocalDateTime.now(), s.getExpiresAt());
                s.setDeadlineWarningSent(true);
                questSubmissionRepository.save(s);
                eventPublisher.publishEvent(new QuestDeadlineWarningEvent(this,
                        s.getUser().getTelegramId(), s.getQuest().getTitle(), minutesLeft));
            } catch (Exception e) {
                log.warn("Failed to send deadline warning for submission {}", s.getId(), e);
            }
        }
    }

    // Еженедельный дайджест — понедельник 10:00 UTC (через 10ч после сброса XP)
    @Scheduled(cron = "0 0 10 * * MON")
    public void sendWeeklyDigests() {
        LocalDateTime weekEnd = LocalDate.now().with(DayOfWeek.MONDAY).atStartOfDay(); // сегодня 00:00
        LocalDateTime weekStart = weekEnd.minusWeeks(1);                                 // прошлый пн 00:00

        // Одним запросом: рейтинг всех пользователей по XP за прошлую неделю
        List<Object[]> rankingRows = questSubmissionRepository.findUserXpRankingBetween(weekStart, weekEnd);
        Map<Long, Long> userXpMap = new HashMap<>();
        Map<Long, Integer> userRankMap = new HashMap<>();
        int rankCounter = 1;
        for (Object[] row : rankingRows) {
            Long userId = (Long) row[0];
            long xp = row[1] == null ? 0L : ((Number) row[1]).longValue();
            userXpMap.put(userId, xp);
            userRankMap.put(userId, rankCounter++);
        }

        long newQuestsCount = questRepository.countCreatedBetween(weekStart, weekEnd);
        long totalSpins = wheelSpinLogRepository.countBetween(weekStart, weekEnd);

        for (AppUser user : userService.allRegisteredUsers()) {
            if (user.isBlocked()) continue;
            try {
                long completedQuests = questSubmissionRepository.countApprovedByUserBetween(user, weekStart, weekEnd);

                if (completedQuests > 0) {
                    long earnedExc = questSubmissionRepository.sumApprovedCoinsByUserBetween(user, weekStart, weekEnd);
                    long lastWeekXp = userXpMap.getOrDefault(user.getId(), 0L);
                    String leagueName = UserService.getLeague(lastWeekXp).displayName;
                    int weeklyRank = userRankMap.getOrDefault(user.getId(), 0);
                    long xpToNextLevel = Math.max(0, userService.nextLevelCeiling(user.getXp()) - user.getXp());
                    eventPublisher.publishEvent(new WeeklyDigestActiveEvent(this,
                            user.getTelegramId(), completedQuests, earnedExc, lastWeekXp,
                            leagueName, weeklyRank, xpToNextLevel));
                } else if (user.getCreatedAt() != null && user.getCreatedAt().isBefore(weekStart)) {
                    eventPublisher.publishEvent(new WeeklyDigestInactiveEvent(this,
                            user.getTelegramId(), newQuestsCount, totalSpins));
                }
            } catch (Exception e) {
                log.warn("Failed to process weekly digest for user {}", user.getTelegramId(), e);
            }
        }
        log.info("Weekly digests dispatched. Active window: {} – {}", weekStart, weekEnd);
    }

    // Снапшот платформы — каждый день в 00:05 (после еженедельного сброса в 00:00 в понедельник)
    @Scheduled(cron = "0 10 0 * * *")
    public void archiveOldNews() {
        try {
            int count = newsService.archiveOldNews(10);
            if (count > 0) log.info("Archived {} old news post(s)", count);
        } catch (Exception e) {
            log.warn("News archiving failed", e);
        }
    }

    @Scheduled(cron = "0 5 0 * * *")
    public void takeDailyPlatformSnapshot() {
        try {
            platformSnapshotService.takeSnapshot();
        } catch (Exception e) {
            log.warn("Daily platform snapshot failed", e);
        }
    }

    // Автопродолжение Season («Battle Pass»), если предыдущий истёк и новый не создали вручную — раз в день
    @Scheduled(cron = "0 15 0 * * *")
    public void checkSeasonContinuity() {
        try {
            seasonService.autoContinueIfLapsed();
        } catch (Exception e) {
            log.error("Season auto-continuation check failed", e);
        }
    }

    // Напоминания об онбординге — каждый час
    @Scheduled(fixedDelay = 3_600_000)
    public void sendOnboardingReminders() {
        LocalDateTime now = LocalDateTime.now();
        for (AppUser user : appUserRepository.findUsersWithIncompleteOnboarding()) {
            try {
                if (user.getOnboardingStartedAt() == null) continue;
                int sent = user.getOnboardingNotificationsSent();
                LocalDateTime lastSent = user.getLastOnboardingNotification();

                boolean shouldSend = false;
                if (sent == 0 && now.isAfter(user.getOnboardingStartedAt().plusMinutes(30))) {
                    shouldSend = true;
                } else if (sent == 1 && lastSent != null && now.isAfter(lastSent.plusHours(24))) {
                    shouldSend = true;
                } else if (sent == 2 && lastSent != null && now.isAfter(lastSent.plusHours(48))) {
                    shouldSend = true;
                }

                if (shouldSend) {
                    user.setOnboardingNotificationsSent(sent + 1);
                    user.setLastOnboardingNotification(now);
                    appUserRepository.save(user);
                    eventPublisher.publishEvent(new OnboardingReminderEvent(this, user.getTelegramId(), sent + 1));
                }
            } catch (Exception e) {
                log.warn("Failed to process onboarding reminder for user {}", user.getTelegramId(), e);
            }
        }
    }

    // Многоуровневые сообщения неактивным (14/30/60 дней без активности) — раз в день.
    // Дополняет, а не заменяет, еженедельный дайджест неактивным (sendWeeklyDigests) — тот лёгкий
    // и без EXC, этот — редкий, с ощутимым подарком за конкретный порог отсутствия.
    @Scheduled(cron = "0 30 0 * * *")
    public void checkDormancyTiers() {
        LocalDate today = LocalDate.now();
        for (AppUser user : userService.allRegisteredUsers()) {
            if (user.isBlocked()) continue;
            try {
                LocalDate lastActive = user.getLastActivityDate();
                if (lastActive == null) continue;
                long daysSince = ChronoUnit.DAYS.between(lastActive, today);

                int highestEligibleTier = 0;
                for (int i = DORMANCY_TIER_DAYS.length; i >= 1; i--) {
                    if (daysSince >= DORMANCY_TIER_DAYS[i - 1]) {
                        highestEligibleTier = i;
                        break;
                    }
                }
                if (highestEligibleTier == 0 || user.getLastDormancyTierNotified() >= highestEligibleTier) {
                    continue;
                }

                long grant = DORMANCY_TIER_EXC[highestEligibleTier - 1];
                userService.addReward(user, 0, grant);
                excTx.log(user, grant, ExcTransactionService.BONUS,
                        "Возвращение после " + daysSince + " дн. отсутствия (тир " + highestEligibleTier + ")");
                user.setLastDormancyTierNotified(highestEligibleTier);
                appUserRepository.save(user);

                eventPublisher.publishEvent(new DormancyReengagementEvent(
                        this, user.getTelegramId(), highestEligibleTier, daysSince, grant));
            } catch (Exception e) {
                log.warn("Failed to process dormancy tier for user {}", user.getTelegramId(), e);
            }
        }
    }

    /** Предупреждение "серия входов под угрозой" — раз в день вечером, тем, кто заходил (отправлял
     * /start) ровно вчера и ещё не сегодня: если не зайти до полуночи, streakDays сбросится в 1
     * (см. UserService.registerActivity). Проверяется именно "вчера", а не "давно" — иначе задел бы
     * и тех, кто вообще забросил бота месяц назад со старым большим streakDays в базе (запрошено
     * 2026-09-14, конкретный сценарий из аудита вовлечённости). */
    @Scheduled(cron = "0 0 20 * * *")
    public void checkStreaksAtRisk() {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        for (AppUser user : userService.allRegisteredUsers()) {
            if (user.isBlocked()) continue;
            try {
                if (user.getStreakDays() < 2) continue;
                if (!yesterday.equals(user.getLastActivityDate())) continue;
                eventPublisher.publishEvent(new StreakAtRiskEvent(this, user.getTelegramId(), user.getStreakDays()));
            } catch (Exception e) {
                log.warn("Failed to process streak-at-risk check for user {}", user.getTelegramId(), e);
            }
        }
    }

    private static final int WEEKEND_BOOST_PERCENT = 100; // +100% = ×2

    /** Буст выходных — с вечера пятницы (запуск задачи) до полуночи понедельника, EXC-награда за
     * одобренный квест удваивается для ВСЕХ игроков (см. QuestRewardBoostEvent / QuestService.computeReward,
     * складывается аддитивно с личным купленным бустом). Анонс в канал — только после одобрения
     * администратора (см. GamePlatformBot.onWeekendBoostStarted), сам буст уже активен сразу, ждать
     * согласования для его работы не нужно. Запрошено 2026-09-14 (аудит вовлечённости). */
    @Scheduled(cron = "0 0 18 * * FRI")
    public void startWeekendBoost() {
        try {
            LocalDateTime start = LocalDateTime.now();
            LocalDateTime end = LocalDate.now().plusDays(3).atStartOfDay(); // пятница + 3 = понедельник 00:00
            questRewardBoostService.create(start, end, WEEKEND_BOOST_PERCENT);
            eventPublisher.publishEvent(new ru.gamebot.platform.event.WeekendBoostStartedEvent(this, WEEKEND_BOOST_PERCENT, end));
        } catch (Exception e) {
            log.error("Failed to start weekend EXC boost", e);
        }
    }

    private static final int TICKET_RAFFLE_MIN_WINNERS = 3;
    private static final int TICKET_RAFFLE_MAX_WINNERS = 5;

    /** Ежедневный розыгрыш билетов колеса фортуны среди тех, кто заходил сегодня (бот, мини-апп или
     * /start) — лёгкий повод зайти "а вдруг выиграю", держит канал живым. Случайное число победителей
     * (3-5, не больше реального числа активных сегодня), по 1 билету каждому — билеты начисляются сразу
     * (WheelService.addTickets), пост в канал — только после одобрения администратора (см.
     * GamePlatformBot.onTicketRaffleDrawn / handleAdminFeedAction), личный выигрыш ждать не должен.
     * Запрошено 2026-09-14 (аудит вовлечённости). */
    @Scheduled(cron = "0 5 20 * * *")
    public void drawDailyTicketRaffle() {
        try {
            LocalDate today = LocalDate.now();
            LocalDateTime todayStart = today.atStartOfDay();
            List<AppUser> activeToday = new java.util.ArrayList<>();
            for (AppUser user : userService.allRegisteredUsers()) {
                if (user.isBlocked()) continue;
                boolean active = today.equals(user.getLastActivityDate())
                        || (user.getLastBotActivityAt() != null && !user.getLastBotActivityAt().isBefore(todayStart))
                        || (user.getLastMiniAppOpenAt() != null && !user.getLastMiniAppOpenAt().isBefore(todayStart));
                if (active) activeToday.add(user);
            }
            if (activeToday.isEmpty()) return;

            int span = TICKET_RAFFLE_MAX_WINNERS - TICKET_RAFFLE_MIN_WINNERS + 1;
            int winnerCount = Math.min(activeToday.size(),
                    TICKET_RAFFLE_MIN_WINNERS + java.util.concurrent.ThreadLocalRandom.current().nextInt(span));
            java.util.Collections.shuffle(activeToday);
            List<AppUser> winners = new java.util.ArrayList<>(activeToday.subList(0, winnerCount));

            for (AppUser winner : winners) {
                wheelService.addTickets(winner, 1, "Ежедневный розыгрыш билетов");
            }
            eventPublisher.publishEvent(new ru.gamebot.platform.event.TicketRaffleDrawnEvent(this, winners));
        } catch (Exception e) {
            log.error("Daily ticket raffle failed", e);
        }
    }

    private static final int QUEST_NUDGE_ACTIVE_WITHIN_DAYS = 3;
    private static final int QUEST_NUDGE_QUEST_GAP_DAYS = 7;
    private static final int QUEST_NUDGE_RESEND_AFTER_DAYS = 7;

    /** "Заходишь, а квесты не берёшь" — отдельная от спячки (DormancyReengagementEvent, срабатывает
     * по общей неактивности) и от онбординга (только для тех, кто вообще не брал первый квест) ниша:
     * игрок реально пользуется ботом/мини-аппом (заходил за последние 3 дня), но квест не берёт уже
     * неделю. lastQuestTakenAt == null исключён намеренно — это тот, кто вообще не начинал, им уже
     * занимается онбординг. Повторно не слать чаще раза в неделю одному и тому же игроку
     * (lastQuestNudgeAt) — запрошено 2026-09-14, конкретный пункт из аудита вовлечённости. */
    @Scheduled(cron = "0 0 10 * * *")
    public void checkQuestGapNudge() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime activeSince = now.minusDays(QUEST_NUDGE_ACTIVE_WITHIN_DAYS);
        LocalDateTime questGapCutoff = now.minusDays(QUEST_NUDGE_QUEST_GAP_DAYS);
        LocalDateTime resendCutoff = now.minusDays(QUEST_NUDGE_RESEND_AFTER_DAYS);
        for (AppUser user : userService.allRegisteredUsers()) {
            if (user.isBlocked()) continue;
            try {
                LocalDateTime lastQuest = user.getLastQuestTakenAt();
                if (lastQuest == null || lastQuest.isAfter(questGapCutoff)) continue;
                boolean activeRecently = (user.getLastBotActivityAt() != null && user.getLastBotActivityAt().isAfter(activeSince))
                        || (user.getLastMiniAppOpenAt() != null && user.getLastMiniAppOpenAt().isAfter(activeSince));
                if (!activeRecently) continue;
                if (user.getLastQuestNudgeAt() != null && user.getLastQuestNudgeAt().isAfter(resendCutoff)) continue;

                long daysSince = ChronoUnit.DAYS.between(lastQuest, now);
                user.setLastQuestNudgeAt(now);
                appUserRepository.save(user);
                eventPublisher.publishEvent(new ru.gamebot.platform.event.QuestGapNudgeEvent(this, user.getTelegramId(), daysSince));
            } catch (Exception e) {
                log.warn("Failed to process quest-gap nudge for user {}", user.getTelegramId(), e);
            }
        }
    }

    private static final int SECOND_QUEST_NUDGE_MIN_DAYS = 2;
    private static final long SECOND_QUEST_NUDGE_EXC = 150;

    /** Точечный пуш через 2-3 дня после первого квеста (аудит вовлечённости, 2026-09-14) — отдельная
     * от checkQuestGapNudge ниша: тот пункт про игроков, которые уже проходили квесты и внезапно
     * остановились, этот — конкретно про самый первый разрыв "первый квест был, второго нет",
     * не привязан к активности в боте/мини-аппе (в отличие от checkQuestGapNudge) — если игрок вообще
     * не заходил после первого квеста, его тем более стоит подтолкнуть бонусом. Разовое напоминание
     * (secondQuestNudgeSentAt) — не троттлинг с повтором, как у checkQuestGapNudge. */
    @Scheduled(cron = "0 0 11 * * *")
    public void checkSecondQuestNudge() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime approvedBefore = now.minusDays(SECOND_QUEST_NUDGE_MIN_DAYS);
        for (AppUser user : userService.allRegisteredUsers()) {
            if (user.isBlocked()) continue;
            try {
                if (user.getCompletedQuests() != 1 || user.getSecondQuestNudgeSentAt() != null) continue;
                var firstApproved = questSubmissionRepository.findFirstByUserAndStatusOrderByUpdatedAtAsc(
                        user, SubmissionStatus.APPROVED);
                if (firstApproved.isEmpty() || firstApproved.get().getUpdatedAt().isAfter(approvedBefore)) continue;

                user.setSecondQuestNudgeSentAt(now);
                userService.addReward(user, 0, SECOND_QUEST_NUDGE_EXC);
                excTx.log(user, SECOND_QUEST_NUDGE_EXC, ExcTransactionService.SECOND_QUEST_NUDGE,
                        "Напоминание про второй квест");
                eventPublisher.publishEvent(new ru.gamebot.platform.event.SecondQuestNudgeEvent(
                        this, user.getTelegramId(), SECOND_QUEST_NUDGE_EXC));
            } catch (Exception e) {
                log.warn("Failed to process second-quest nudge for user {}", user.getTelegramId(), e);
            }
        }
    }

    private static final int REFERRAL_INACTIVITY_DAYS = 14;

    // Друг молчит 14+ дней без одобренного квеста — приостанавливаем комиссию рефереру, уведомляем один раз
    @Scheduled(cron = "0 20 0 * * *")
    public void checkReferralFriendActivity() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(REFERRAL_INACTIVITY_DAYS);
        for (AppUser invited : appUserRepository.findAllByReferredByTelegramIdIsNotNullAndReferralActiveTrue()) {
            try {
                if (questSubmissionRepository.existsApprovedByUserSince(invited, cutoff)) continue;
                invited.setReferralActive(false);
                appUserRepository.save(invited);
                eventPublisher.publishEvent(new ru.gamebot.platform.event.ReferralFriendInactiveEvent(
                        this, invited.getReferredByTelegramId(), invited.getNickname()));
            } catch (Exception e) {
                log.warn("Failed to check referral activity for user {}", invited.getTelegramId(), e);
            }
        }
    }

    private void notifyExpired(List<Object[]> rows) {
        for (Object[] row : rows) {
            try {
                Long telegramId = (Long) row[0];
                String gameName = (String) row[1];
                String questTitle = (String) row[2];
                eventPublisher.publishEvent(new CooldownExpiredEvent(this, telegramId, gameName, questTitle));
            } catch (Exception e) {
                log.warn("Failed to send cooldown notification to user {}", row[0], e);
            }
        }
    }
}
