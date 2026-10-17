package ru.gamebot.platform.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.ClubGoal;
import ru.gamebot.platform.domain.model.ClubGoalClaim;
import ru.gamebot.platform.domain.model.DailyCode;
import ru.gamebot.platform.domain.model.DailyCodeClaim;
import ru.gamebot.platform.domain.repository.ClubGoalClaimRepository;
import ru.gamebot.platform.domain.repository.ClubGoalRepository;
import ru.gamebot.platform.domain.repository.DailyCodeClaimRepository;
import ru.gamebot.platform.domain.repository.DailyCodeRepository;

/** «Код дня» и «Цель клуба» (2026-10-17): способы привести игроков из бота в канал.
 *  Код дня: спрятан в посте канала (пост создаётся автоматически и идёт админу на согласование), игрок вводит его в боте и получает
 *  билет и XP; на каждый 7-й день подряд добавляется EXC. Цель клуба: общая цель по реакциям под постом дня (счётчик из Telegram) и
 *  «портал» на квест (спонсорский и т.п.) без собственных бонусов. Реакции бот привязать к игроку не может, поэтому награду за цель
 *  получают те, кто сегодня ввёл код этого поста, - так расходы пропорциональны реальному заходу в канал. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClubActivityService {

    public static final int CODE_XP = 20;
    public static final int CODE_TICKETS = 1;
    public static final int STREAK_BONUS_EVERY_DAYS = 7;
    public static final long STREAK_BONUS_EXC = 100;
    public static final int CODE_TTL_HOURS = 24;

    public static final int GOAL_TARGET_REACTIONS = 30;
    public static final long GOAL_REWARD_EXC = 100;
    public static final int GOAL_REWARD_TICKETS = 1;
    public static final int GOAL_TTL_HOURS = 48;
    /** Сколько часов после выполнения цели можно забрать награду. */
    public static final int GOAL_CLAIM_WINDOW_HOURS = 24;

    private static final String[] CODE_WORDS = {"EXI", "LOOT", "RAID", "BOSS", "CLAN", "RUSH", "QUEST", "LEVEL", "SKILL", "COMBO"};

    private final DailyCodeRepository codeRepository;
    private final DailyCodeClaimRepository codeClaimRepository;
    private final ClubGoalRepository goalRepository;
    private final ClubGoalClaimRepository goalClaimRepository;
    private final UserService userService;

    // ───────────────────────── код дня ─────────────────────────

    public String generateCode() {
        return CODE_WORDS[ThreadLocalRandom.current().nextInt(CODE_WORDS.length)] + "-" + (10 + ThreadLocalRandom.current().nextInt(90));
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toUpperCase().replace('Ё', 'Е').replaceAll("[^A-Z0-9А-Я]", "");
    }

    /** Вызывается после публикации поста с кодом: заводит код (действует 24 ч) и цель по реакциям под этим постом (закрывая прежнюю). */
    @Transactional
    public DailyCode onCodePostPublished(String code, Integer channelMessageId) {
        LocalDateTime now = LocalDateTime.now();
        DailyCode dc = new DailyCode();
        dc.setCode(code);
        dc.setChannelMessageId(channelMessageId);
        dc.setPublishedAt(now);
        dc.setValidUntil(now.plusHours(CODE_TTL_HOURS));
        dc = codeRepository.save(dc);

        for (ClubGoal old : goalRepository.findAllByGoalTypeAndStatus(ClubGoal.REACTIONS, ClubGoal.ACTIVE)) {
            old.setStatus(ClubGoal.EXPIRED);
            goalRepository.save(old);
        }
        ClubGoal goal = new ClubGoal();
        goal.setGoalType(ClubGoal.REACTIONS);
        goal.setTitle("Цель дня: реакции под постом");
        goal.setTargetCount(GOAL_TARGET_REACTIONS);
        goal.setChannelMessageId(channelMessageId);
        goal.setDailyCodeId(dc.getId());
        goal.setRewardExc(GOAL_REWARD_EXC);
        goal.setRewardTickets(GOAL_REWARD_TICKETS);
        goal.setExpiresAt(now.plusHours(GOAL_TTL_HOURS));
        goalRepository.save(goal);
        return dc;
    }

    public Optional<DailyCode> activeCode() {
        return codeRepository.findFirstByValidUntilAfterOrderByIdDesc(LocalDateTime.now());
    }

    public Optional<DailyCode> latestCode() {
        return codeRepository.findFirstByOrderByIdDesc();
    }

    /** Серия дней подряд с введённым кодом: считается до сегодня (если сегодня уже вводил) или до вчера (если ещё нет); 0 - серии нет. */
    public int currentStreak(Long userId) {
        List<DailyCodeClaim> claims = codeClaimRepository.findTop30ByUserIdOrderByClaimDateDesc(userId);
        if (claims.isEmpty()) return 0;
        LocalDate today = LocalDate.now();
        LocalDate expected = claims.get(0).getClaimDate();
        if (!expected.equals(today) && !expected.equals(today.minusDays(1))) return 0;
        int streak = 0;
        for (DailyCodeClaim c : claims) {
            if (!c.getClaimDate().equals(expected)) break;
            streak++;
            expected = expected.minusDays(1);
        }
        return streak;
    }

    public boolean claimedToday(Long userId) {
        return codeClaimRepository.existsByUserIdAndClaimDate(userId, LocalDate.now());
    }

    public enum CodeStatus { OK, NO_ACTIVE_CODE, WRONG, ALREADY_TODAY, NOT_REGISTERED }

    public record CodeResult(CodeStatus status, int streak, long bonusExc) {}

    @Transactional
    public CodeResult claimCode(AppUser user, String input) {
        if (!user.isRegistrationCompleted()) return new CodeResult(CodeStatus.NOT_REGISTERED, 0, 0);
        Optional<DailyCode> active = activeCode();
        if (active.isEmpty()) return new CodeResult(CodeStatus.NO_ACTIVE_CODE, 0, 0);
        if (codeClaimRepository.existsByUserIdAndClaimDate(user.getId(), LocalDate.now())) {
            return new CodeResult(CodeStatus.ALREADY_TODAY, currentStreak(user.getId()), 0);
        }
        DailyCode code = active.get();
        if (!normalize(code.getCode()).equals(normalize(input))) return new CodeResult(CodeStatus.WRONG, 0, 0);

        int streak = currentStreak(user.getId()) + 1;
        DailyCodeClaim claim = new DailyCodeClaim();
        claim.setUserId(user.getId());
        claim.setCodeId(code.getId());
        claim.setClaimDate(LocalDate.now());
        claim.setClaimedAt(LocalDateTime.now());
        try {
            codeClaimRepository.saveAndFlush(claim); // уникальность (игрок, день): двойное нажатие не выдаст награду дважды
        } catch (DataIntegrityViolationException e) {
            return new CodeResult(CodeStatus.ALREADY_TODAY, currentStreak(user.getId()), 0);
        }
        long bonusExc = streak % STREAK_BONUS_EVERY_DAYS == 0 ? STREAK_BONUS_EXC : 0;
        userService.addReward(user, CODE_XP, bonusExc, CODE_TICKETS, ExcTransactionService.DAILY_CODE, "Код дня (серия " + streak + ")");
        return new CodeResult(CodeStatus.OK, streak, bonusExc);
    }

    // ───────────────────────── цель клуба ─────────────────────────

    /** Последняя цель по реакциям; просроченную активную помечает EXPIRED. */
    @Transactional
    public Optional<ClubGoal> currentReactionsGoal() {
        Optional<ClubGoal> opt = goalRepository.findFirstByGoalTypeOrderByIdDesc(ClubGoal.REACTIONS);
        opt.ifPresent(g -> {
            if (ClubGoal.ACTIVE.equals(g.getStatus()) && g.getExpiresAt() != null && g.getExpiresAt().isBefore(LocalDateTime.now())) {
                g.setStatus(ClubGoal.EXPIRED);
                goalRepository.save(g);
            }
        });
        return opt;
    }

    public Optional<ClubGoal> activeLinkGoal() {
        return goalRepository.findAllByGoalTypeAndStatus(ClubGoal.LINK, ClubGoal.ACTIVE).stream()
                .max(java.util.Comparator.comparing(ClubGoal::getId));
    }

    /** Telegram прислал общее число реакций под постом канала. Возвращает цель, которая только что стала выполненной (иначе empty). */
    @Transactional
    public Optional<ClubGoal> onReactionCount(Integer messageId, int total) {
        Optional<ClubGoal> completed = Optional.empty();
        for (ClubGoal g : goalRepository.findAllByChannelMessageIdAndStatus(messageId, ClubGoal.ACTIVE)) {
            g.setCountUpdatedAt(LocalDateTime.now());
            if (total > g.getCurrentCount()) g.setCurrentCount(total);
            if (g.getCurrentCount() >= g.getTargetCount()
                    && (g.getExpiresAt() == null || g.getExpiresAt().isAfter(LocalDateTime.now()))) {
                g.setStatus(ClubGoal.DONE);
                g.setCompletedAt(LocalDateTime.now());
                completed = Optional.of(g);
            }
            goalRepository.save(g);
        }
        return completed;
    }

    public boolean goalClaimable(ClubGoal g) {
        return ClubGoal.DONE.equals(g.getStatus()) && g.getCompletedAt() != null
                && g.getCompletedAt().plusHours(GOAL_CLAIM_WINDOW_HOURS).isAfter(LocalDateTime.now());
    }

    public boolean goalClaimed(ClubGoal g, Long userId) {
        return goalClaimRepository.existsByGoalIdAndUserId(g.getId(), userId);
    }

    /** Награду могут забрать те, кто ввёл код дня этого поста (у цели без кода - любой зарегистрированный игрок). */
    public boolean eligibleForGoal(ClubGoal g, Long userId) {
        return g.getDailyCodeId() == null || codeClaimRepository.existsByUserIdAndCodeId(userId, g.getDailyCodeId());
    }

    public enum GoalClaimStatus { OK, NOT_READY, NOT_ELIGIBLE, ALREADY, NOT_REGISTERED }

    public record GoalClaimResult(GoalClaimStatus status, long exc, int tickets) {}

    @Transactional
    public GoalClaimResult claimGoal(AppUser user, Long goalId) {
        if (!user.isRegistrationCompleted()) return new GoalClaimResult(GoalClaimStatus.NOT_REGISTERED, 0, 0);
        ClubGoal g = goalRepository.findById(goalId).orElse(null);
        if (g == null || !ClubGoal.REACTIONS.equals(g.getGoalType()) || !goalClaimable(g)) {
            return new GoalClaimResult(GoalClaimStatus.NOT_READY, 0, 0);
        }
        if (goalClaimRepository.existsByGoalIdAndUserId(g.getId(), user.getId())) return new GoalClaimResult(GoalClaimStatus.ALREADY, 0, 0);
        if (!eligibleForGoal(g, user.getId())) return new GoalClaimResult(GoalClaimStatus.NOT_ELIGIBLE, 0, 0);
        ClubGoalClaim claim = new ClubGoalClaim();
        claim.setGoalId(g.getId());
        claim.setUserId(user.getId());
        claim.setClaimedAt(LocalDateTime.now());
        try {
            goalClaimRepository.saveAndFlush(claim);
        } catch (DataIntegrityViolationException e) {
            return new GoalClaimResult(GoalClaimStatus.ALREADY, 0, 0);
        }
        userService.addReward(user, 0, g.getRewardExc(), g.getRewardTickets(), ExcTransactionService.CLUB_GOAL, "Цель клуба выполнена");
        return new GoalClaimResult(GoalClaimStatus.OK, g.getRewardExc(), g.getRewardTickets());
    }

    // ───────────────────────── админ ─────────────────────────

    /** Цель-портал в квест: заменяет прежнюю активную. Никаких бонусов и прогресса - только заголовок, текст и кнопка. */
    @Transactional
    public ClubGoal createLinkGoal(Long questId, String title, String description) {
        closeLinkGoal();
        ClubGoal g = new ClubGoal();
        g.setGoalType(ClubGoal.LINK);
        g.setQuestId(questId);
        g.setTitle(title.length() > 160 ? title.substring(0, 160) : title);
        g.setDescription(description.length() > 1000 ? description.substring(0, 1000) : description);
        return goalRepository.save(g);
    }

    @Transactional
    public int closeLinkGoal() {
        int n = 0;
        for (ClubGoal g : goalRepository.findAllByGoalTypeAndStatus(ClubGoal.LINK, ClubGoal.ACTIVE)) {
            g.setStatus(ClubGoal.CLOSED);
            goalRepository.save(g);
            n++;
        }
        return n;
    }

    public long goalClaimsCount(Long goalId) {
        return goalClaimRepository.countByGoalId(goalId);
    }

    public long codeClaimsCount(Long codeId) {
        return codeClaimRepository.countByCodeId(codeId);
    }
}
