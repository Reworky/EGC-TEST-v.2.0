package ru.gamebot.platform.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Squad;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.model.SquadJoinRequest;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;
import ru.gamebot.platform.domain.repository.SquadJoinRequestRepository;
import ru.gamebot.platform.domain.repository.SquadRepository;
import ru.gamebot.platform.event.SquadGoalReachedEvent;
import ru.gamebot.platform.event.SquadJoinDecisionEvent;
import ru.gamebot.platform.event.SquadJoinRequestEvent;
import ru.gamebot.platform.event.SquadMilestoneReachedEvent;
import ru.gamebot.platform.event.SquadPrizeEvent;
import ru.gamebot.platform.event.SquadReferralBonusEvent;

@Slf4j
@Service
@RequiredArgsConstructor
public class SquadService {

    /** Практически безлимит на нынешнем масштабе проекта (~3600 игроков всего) — раньше было 5,
     *  снято ради вирусного роста по образцу крупных Telegram-сообществ/кланов (см. rewardTopSquad:
     *  недельный приз ограничен PRIZE_MAX_RECIPIENTS, а не размером отряда, поэтому рост отряда
     *  не увеличивает расходы клуба). */
    private static final int MAX_MEMBERS = 500;
    private static final int MIN_MEMBERS = 2;
    public static final long WEEKLY_PRIZE_POOL = 10_000L;
    /** Приз получают не "все участники", а топ-N по недельному XP — иначе при большом отряде
     *  целочисленное деление WEEKLY_PRIZE_POOL/members.size() молча схлопывается к 0 на человека.
     *  Для отрядов ≤10 человек (весь текущий состав игроков) поведение идентично старому. */
    public static final int PRIZE_MAX_RECIPIENTS = 10;
    private static final long REFERRAL_SQUAD_BONUS_POINTS = 100;
    private static final int REFERRAL_SQUAD_JOIN_WINDOW_DAYS = 7;

    /** Разовые бонусы за рост отряда (ТЗ EGC_TZ_otryady, 2026-10-02): диагностика подтвердила, что
     *  удержание у "в отряде" в разы выше, чем у "без отряда", даже при среднем размере отряда всего
     *  1,5 человека - большинство "отрядов" сейчас теги на 1-2 людях, не команды. Суммы скромные
     *  (сопоставимы с REFERRAL_SQUAD_BONUS_POINTS по порядку цены за действие), чтобы не раздувать
     *  Payout Pool - это подталкивающий нудж, а не основной источник дохода игрока. */
    public static final long SQUAD_MILESTONE_3_BONUS_PER_MEMBER = 200;
    public static final long SQUAD_MILESTONE_5_BONUS_PER_MEMBER = 150;

    private final SquadRepository squadRepository;
    private final AppUserRepository appUserRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ExcTransactionService excTx;
    private final QuestSubmissionRepository submissionRepository;
    private final SquadJoinRequestRepository joinRequestRepository;

    /** Заявка в отряд висит не дольше стольких дней (старые не показываются капитану и не принимаются). */
    public static final int JOIN_REQUEST_TTL_DAYS = 14;
    /** Сколько заявок в разные отряды игрок может держать одновременно. */
    public static final int MAX_PENDING_REQUESTS_PER_USER = 3;

    // ── Командная цель недели (2026-10-03): отряд вместе набирает N одобренных квестов за неделю ──
    /** Цель = квестов на участника; минимум GOAL_MIN. Считается от размера, чтобы малые отряды тоже могли победить
     *  (приз топ-отряда доступен только лидерам рейтинга). */
    public static final int GOAL_PER_MEMBER = 2;
    public static final int GOAL_MIN = 4;
    /** Участвуют отряды от 3 человек (так двойник-аккаунты на двоих не фармят награду). */
    public static final int GOAL_MIN_MEMBERS = 3;
    /** Награда каждому участнику с хотя бы одним квестом за неделю, когда цель выполнена. Верхняя граница расхода:
     *  250 EXC x игроков в отрядах (на нынешних ~80 игроков - до 20 000 EXC в неделю). */
    public static final long GOAL_BONUS_PER_MEMBER = 250;

    public record GoalProgress(boolean eligible, long done, long target, boolean reached, long bonusPerMember) {}

    public Optional<Squad> findById(Long id) {
        return squadRepository.findById(id);
    }

    public Optional<Squad> findByUser(AppUser user) {
        if (user.getSquadId() == null) return Optional.empty();
        return squadRepository.findById(user.getSquadId())
                .filter(s -> "ACTIVE".equals(s.getStatus()));
    }

    /** Поиск для админки: сначала точное совпадение по названию, иначе — если частичный ввод
     *  однозначно указывает на один отряд — берём его. Несколько совпадений или ни одного — пусто. */
    public Optional<Squad> findByNameForAdmin(String query) {
        String trimmed = query.trim();
        Optional<Squad> exact = squadRepository.findByNameIgnoreCase(trimmed);
        if (exact.isPresent()) return exact;
        List<Squad> matches = squadRepository.findAllByNameContainingIgnoreCase(trimmed);
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    public List<AppUser> getMembers(Squad squad) {
        return appUserRepository.findAllBySquadId(squad.getId());
    }

    public long memberCount(Squad squad) {
        return appUserRepository.countBySquadId(squad.getId());
    }

    public long squadWeeklyXp(Squad squad) {
        return getMembers(squad).stream().mapToLong(AppUser::getWeeklyXp).sum() + squad.getWeeklyBonusPoints();
    }

    public boolean isNameTaken(String name) {
        return squadRepository.existsByNameIgnoreCase(name.trim());
    }

    @Transactional
    public Squad create(AppUser captain, String name) {
        if (captain.getSquadId() != null) {
            throw new IllegalStateException("Вы уже состоите в отряде.");
        }
        if (isNameTaken(name)) {
            throw new IllegalArgumentException("Отряд с таким названием уже существует.");
        }
        Squad squad = new Squad();
        squad.setName(name.trim());
        squad.setCaptainTelegramId(captain.getTelegramId());
        squad.setInviteCode(generateInviteCode());
        squad.setStatus("ACTIVE");
        squad.setOpenRecruitment(true);
        squad.setCreatedAt(LocalDateTime.now());
        squad = squadRepository.save(squad);

        captain.setSquadId(squad.getId());
        appUserRepository.save(captain);
        return squad;
    }

    @Transactional
    public Squad join(AppUser user, Long squadId) {
        if (user.getSquadId() != null) {
            throw new IllegalStateException("Вы уже состоите в отряде. Покиньте его перед вступлением.");
        }
        Squad squad = squadRepository.findById(squadId)
                .orElseThrow(() -> new IllegalArgumentException("Отряд не найден."));
        if (!"ACTIVE".equals(squad.getStatus())) {
            throw new IllegalStateException("Этот отряд расформирован.");
        }
        long count = memberCount(squad);
        if (count >= MAX_MEMBERS) {
            throw new IllegalStateException("Отряд уже заполнен (" + MAX_MEMBERS + "/" + MAX_MEMBERS + " игроков).");
        }
        user.setSquadId(squad.getId());
        appUserRepository.save(user);
        cancelPendingRequests(user);
        awardReferralSquadBonus(user, squad);
        awardSizeMilestoneIfReached(squad);
        return squad;
    }

    /** Разовый бонус всем текущим участникам, когда отряд ВПЕРВЫЕ достигает 3 или 5 человек (ТЗ
     *  EGC_TZ_otryady, 2026-10-02) - флаги на Squad защищают от повторной выплаты при колебании состава.
     *  Проверяет оба порога за один вызов (join() увеличивает размер максимум на 1, но так надёжнее,
     *  если когда-нибудь появится групповое вступление). */
    private void awardSizeMilestoneIfReached(Squad squad) {
        long count = memberCount(squad);
        if (count >= 3 && !squad.isMilestone3Awarded()) {
            squad.setMilestone3Awarded(true);
            squadRepository.save(squad);
            awardSizeMilestone(squad, 3, SQUAD_MILESTONE_3_BONUS_PER_MEMBER);
        }
        if (count >= 5 && !squad.isMilestone5Awarded()) {
            squad.setMilestone5Awarded(true);
            squadRepository.save(squad);
            awardSizeMilestone(squad, 5, SQUAD_MILESTONE_5_BONUS_PER_MEMBER);
        }
    }

    private void awardSizeMilestone(Squad squad, int size, long bonusPerMember) {
        List<AppUser> members = getMembers(squad);
        for (AppUser member : members) {
            excTx.creditExc(member, bonusPerMember, ExcTransactionService.SQUAD_MILESTONE,
                    "Отряд «" + squad.getName() + "» достиг " + size + " человек");
        }
        eventPublisher.publishEvent(new SquadMilestoneReachedEvent(this, squad, members, size, bonusPerMember));
    }

    /** Модуль "Реферал усиливает Отряд" (максимизация рефералки, Модуль 2): если вступивший был приглашён
     *  кем-то и вступает именно в отряд ПРИГЛАСИВШЕГО (не в любой отряд вообще) в течение
     *  {@link #REFERRAL_SQUAD_JOIN_WINDOW_DAYS} дней с момента СВОЕЙ регистрации — отряду начисляются
     *  бонусные очки к недельному рейтингу и все участники получают уведомление. */
    private void awardReferralSquadBonus(AppUser invitedUser, Squad squad) {
        Long referrerTelegramId = invitedUser.getReferredByTelegramId();
        if (referrerTelegramId == null) return;
        if (invitedUser.getCreatedAt() == null
                || invitedUser.getCreatedAt().plusDays(REFERRAL_SQUAD_JOIN_WINDOW_DAYS).isBefore(LocalDateTime.now())) {
            return; // окно "за счёт приглашения" истекло
        }
        AppUser referrer = appUserRepository.findByTelegramId(referrerTelegramId).orElse(null);
        if (referrer == null || referrer.getSquadId() == null || !referrer.getSquadId().equals(squad.getId())) {
            return; // вступил не в отряд именно пригласившего
        }
        squad.setWeeklyBonusPoints(squad.getWeeklyBonusPoints() + REFERRAL_SQUAD_BONUS_POINTS);
        squadRepository.save(squad);
        List<AppUser> members = getMembers(squad);
        eventPublisher.publishEvent(new SquadReferralBonusEvent(this, squad, members, invitedUser, REFERRAL_SQUAD_BONUS_POINTS));
    }

    /** Раз в неделю (см. WeeklyResetScheduler), после того как rewardTopSquad() прочитал итоговый счёт —
     *  иначе бонусные очки накапливались бы бессрочно вместо действия только на текущую неделю. */
    @Transactional
    public void resetWeeklyBonusPoints() {
        List<Squad> all = squadRepository.findAll();
        for (Squad s : all) {
            s.setWeeklyBonusPoints(0);
        }
        squadRepository.saveAll(all);
    }

    @Transactional
    public Squad joinByInviteCode(AppUser user, String code) {
        Squad squad = squadRepository.findByInviteCode(code.trim().toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("Отряд с таким кодом не найден."));
        return join(user, squad.getId());
    }

    @Transactional
    public void leave(AppUser user) {
        if (user.getSquadId() == null) {
            throw new IllegalStateException("Вы не состоите ни в одном отряде.");
        }
        Squad squad = squadRepository.findById(user.getSquadId()).orElse(null);
        user.setSquadId(null);
        appUserRepository.save(user);

        if (squad != null && user.getTelegramId().equals(squad.getCaptainTelegramId())) {
            // Captain left — transfer to next member or disband
            List<AppUser> remaining = appUserRepository.findAllBySquadId(squad.getId());
            if (remaining.isEmpty()) {
                squad.setStatus("DISBANDED");
                squadRepository.save(squad);
            } else {
                squad.setCaptainTelegramId(remaining.get(0).getTelegramId());
                squadRepository.save(squad);
            }
        }
    }

    @Transactional
    public void disband(AppUser captain) {
        if (captain.getSquadId() == null) {
            throw new IllegalStateException("Вы не состоите в отряде.");
        }
        Squad squad = squadRepository.findById(captain.getSquadId())
                .orElseThrow(() -> new IllegalArgumentException("Отряд не найден."));
        if (!captain.getTelegramId().equals(squad.getCaptainTelegramId())) {
            throw new IllegalStateException("Только капитан может расформировать отряд.");
        }
        List<AppUser> members = appUserRepository.findAllBySquadId(squad.getId());
        for (AppUser member : members) {
            member.setSquadId(null);
        }
        appUserRepository.saveAll(members);
        squad.setStatus("DISBANDED");
        squadRepository.save(squad);
    }

    @Transactional
    public void kick(AppUser captain, Long memberTelegramId) {
        if (captain.getSquadId() == null) throw new IllegalStateException("Вы не в отряде.");
        Squad squad = squadRepository.findById(captain.getSquadId())
                .orElseThrow(() -> new IllegalArgumentException("Отряд не найден."));
        if (!captain.getTelegramId().equals(squad.getCaptainTelegramId())) {
            throw new IllegalStateException("Только капитан может исключать участников.");
        }
        if (captain.getTelegramId().equals(memberTelegramId)) {
            throw new IllegalStateException("Нельзя исключить самого себя.");
        }
        AppUser member = appUserRepository.findByTelegramId(memberTelegramId)
                .orElseThrow(() -> new IllegalArgumentException("Игрок не найден."));
        if (!squad.getId().equals(member.getSquadId())) {
            throw new IllegalStateException("Этот игрок не состоит в вашем отряде.");
        }
        member.setSquadId(null);
        appUserRepository.save(member);
    }

    /** Returns top-20 active squads sorted by weeklyXp descending. */
    public List<SquadRankEntry> getLeaderboard() {
        List<Squad> active = squadRepository.findAllByStatus("ACTIVE");
        return active.stream()
                .map(s -> {
                    long xp = squadWeeklyXp(s);
                    long count = memberCount(s);
                    return new SquadRankEntry(s, xp, count);
                })
                .filter(e -> e.weeklyXp() > 0)
                .sorted(Comparator.comparingLong(SquadRankEntry::weeklyXp).reversed())
                .limit(20)
                .toList();
    }

    /** Сумма ПОСТОЯННОГО (не недельного) XP текущих участников — не сбрасывается никогда,
     *  в отличие от squadWeeklyXp(). Показывает общую "прокачанность" отряда, а не только
     *  последнюю неделю. Без weeklyBonusPoints — тот бонус специфичен для недельного рейтинга. */
    public long squadTotalXp(Squad squad) {
        return getMembers(squad).stream().mapToLong(AppUser::getXp).sum();
    }

    /** Общий (не сбрасывающийся) рейтинг топ-20 активных отрядов по сумме постоянного XP участников. */
    public List<SquadRankEntry> getOverallLeaderboard() {
        List<Squad> active = squadRepository.findAllByStatus("ACTIVE");
        return active.stream()
                .map(s -> {
                    long xp = squadTotalXp(s);
                    long count = memberCount(s);
                    return new SquadRankEntry(s, xp, count);
                })
                .filter(e -> e.weeklyXp() > 0)
                .sorted(Comparator.comparingLong(SquadRankEntry::weeklyXp).reversed())
                .limit(20)
                .toList();
    }

    /** Called before weekly XP reset. Pays 10 000 EXC split equally to members of the top squad. */
    @Transactional
    public void rewardTopSquad() {
        List<SquadRankEntry> leaderboard = getLeaderboard();
        if (leaderboard.isEmpty()) return;

        SquadRankEntry top = leaderboard.get(0);
        if (top.memberCount() < MIN_MEMBERS) return; // need at least 2 to qualify

        List<AppUser> members = appUserRepository.findAllBySquadId(top.squad().getId());
        if (members.isEmpty()) return;

        List<AppUser> sorted = members.stream()
                .sorted(Comparator.comparingLong(AppUser::getWeeklyXp).reversed())
                .toList();
        int payoutCount = Math.min(sorted.size(), PRIZE_MAX_RECIPIENTS);
        List<AppUser> winners = sorted.subList(0, payoutCount);
        long prizePerMember = WEEKLY_PRIZE_POOL / payoutCount;
        // До рефакторинга 2026-10-01 эта выплата не логировалась в exc_transactions вообще (найдено
        // при миграции на единую точку начисления) - теперь каждому победителю отдельная запись SQUAD_PRIZE.
        for (AppUser member : winners) {
            excTx.creditExc(member, prizePerMember, ExcTransactionService.SQUAD_PRIZE,
                    "Приз топ-отряда недели: " + top.squad().getName());
        }
        log.info("Squad weekly prize: {} EXC each to top {} of {} members of squad '{}' (total XP: {})",
                prizePerMember, payoutCount, members.size(), top.squad().getName(), top.weeklyXp());

        eventPublisher.publishEvent(new SquadPrizeEvent(this, top.squad(), winners, prizePerMember, top.weeklyXp()));
    }

    // ── Флаг отряда ─────────────────────────────────────────────────────────────

    /** Капитан ставит (fileId) или убирает (null) флаг своего отряда. */
    @Transactional
    public Squad setFlag(AppUser captain, String fileId) {
        Squad squad = findByUser(captain).orElseThrow(() -> new IllegalStateException("Вы не состоите ни в одном отряде."));
        if (!captain.getTelegramId().equals(squad.getCaptainTelegramId())) {
            throw new IllegalStateException("Менять флаг может только капитан.");
        }
        squad.setFlagFileId(fileId);
        return squadRepository.save(squad);
    }

    /** Модерация: админ убирает флаг любого отряда. */
    @Transactional
    public Squad clearFlag(Long squadId) {
        Squad squad = squadRepository.findById(squadId).orElseThrow(() -> new IllegalArgumentException("Отряд не найден."));
        squad.setFlagFileId(null);
        return squadRepository.save(squad);
    }

    // ── Открытый набор и каталог ────────────────────────────────────────────────

    @Transactional
    public Squad setRecruitment(AppUser captain, boolean open) {
        Squad squad = findByUser(captain).orElseThrow(() -> new IllegalStateException("Вы не состоите ни в одном отряде."));
        if (!captain.getTelegramId().equals(squad.getCaptainTelegramId())) {
            throw new IllegalStateException("Менять набор может только капитан.");
        }
        squad.setOpenRecruitment(open);
        return squadRepository.save(squad);
    }

    /** Каталог «Найти отряд»: ВСЕ активные не заполненные отряды (открытые вступают сразу, закрытые — по заявке),
     *  по недельной активности. query — часть названия для поиска (пусто — весь каталог). */
    public List<SquadRankEntry> findCatalog(String query) {
        String q = query == null ? "" : query.trim().toLowerCase();
        return squadRepository.findAll().stream()
                .filter(s -> "ACTIVE".equals(s.getStatus()) && (q.isEmpty() || s.getName().toLowerCase().contains(q)))
                .map(s -> new SquadRankEntry(s, squadWeeklyXp(s), memberCount(s)))
                .filter(e -> e.memberCount() > 0 && e.memberCount() < MAX_MEMBERS)
                .sorted(Comparator.comparingLong(SquadRankEntry::weeklyXp).reversed()
                        .thenComparing(Comparator.comparingLong(SquadRankEntry::memberCount).reversed()))
                .limit(100)
                .toList();
    }

    // ── Заявки в отряды с закрытым набором ──────────────────────────────────────

    private static LocalDateTime requestsSince() {
        return LocalDateTime.now().minusDays(JOIN_REQUEST_TTL_DAYS);
    }

    /** Есть ли у игрока живая заявка в этот отряд (для подписи кнопки «Заявка отправлена»). */
    public boolean hasPendingRequest(AppUser user, Long squadId) {
        return joinRequestRepository.findAllByUserIdAndStatus(user.getId(), SquadJoinRequest.PENDING).stream()
                .anyMatch(r -> r.getSquadId().equals(squadId) && r.getCreatedAt().isAfter(requestsSince()));
    }

    @Transactional
    public SquadJoinRequest requestJoin(AppUser user, Long squadId) {
        if (user.getSquadId() != null) {
            throw new IllegalStateException("Вы уже состоите в отряде. Покиньте его перед вступлением.");
        }
        Squad squad = squadRepository.findById(squadId).orElseThrow(() -> new IllegalArgumentException("Отряд не найден."));
        if (!"ACTIVE".equals(squad.getStatus())) {
            throw new IllegalStateException("Этот отряд расформирован.");
        }
        if (squad.isOpenRecruitment()) {
            throw new IllegalStateException("В этот отряд можно вступить сразу — заявка не нужна.");
        }
        if (memberCount(squad) >= MAX_MEMBERS) {
            throw new IllegalStateException("Отряд уже заполнен.");
        }
        List<SquadJoinRequest> mine = joinRequestRepository.findAllByUserIdAndStatus(user.getId(), SquadJoinRequest.PENDING).stream()
                .filter(r -> r.getCreatedAt().isAfter(requestsSince())).toList();
        if (mine.stream().anyMatch(r -> r.getSquadId().equals(squadId))) {
            throw new IllegalStateException("Вы уже подали заявку в этот отряд — ждите решения капитана.");
        }
        if (mine.size() >= MAX_PENDING_REQUESTS_PER_USER) {
            throw new IllegalStateException("Можно держать не больше " + MAX_PENDING_REQUESTS_PER_USER + " заявок одновременно.");
        }
        SquadJoinRequest request = new SquadJoinRequest();
        request.setSquadId(squadId);
        request.setUserId(user.getId());
        request = joinRequestRepository.save(request);
        eventPublisher.publishEvent(new SquadJoinRequestEvent(this, squad, user, request.getId()));
        return request;
    }

    /** Живые заявки в отряд капитана (не старше JOIN_REQUEST_TTL_DAYS), старые сначала. */
    public List<SquadJoinRequest> pendingRequests(Squad squad) {
        return joinRequestRepository.findAllBySquadIdAndStatusAndCreatedAtAfterOrderByCreatedAtAsc(
                squad.getId(), SquadJoinRequest.PENDING, requestsSince());
    }

    public long pendingRequestCount(Squad squad) {
        return joinRequestRepository.countBySquadIdAndStatusAndCreatedAtAfter(squad.getId(), SquadJoinRequest.PENDING, requestsSince());
    }

    public Optional<AppUser> findApplicant(SquadJoinRequest request) {
        return appUserRepository.findById(request.getUserId());
    }

    /** Капитан решает по заявке. approve=true — игрок вступает в отряд (с теми же проверками и бонусами, что у обычного
     *  вступления), false — заявка отклоняется. В обоих случаях заявителю уходит уведомление (SquadJoinDecisionEvent). */
    @Transactional
    public SquadJoinRequest decideRequest(AppUser captain, Long requestId, boolean approve) {
        SquadJoinRequest request = joinRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена."));
        Squad squad = squadRepository.findById(request.getSquadId()).orElseThrow(() -> new IllegalArgumentException("Отряд не найден."));
        if (!captain.getTelegramId().equals(squad.getCaptainTelegramId())) {
            throw new IllegalStateException("Решать по заявкам может только капитан.");
        }
        if (!SquadJoinRequest.PENDING.equals(request.getStatus())) {
            throw new IllegalStateException("Заявка уже обработана.");
        }
        AppUser applicant = appUserRepository.findById(request.getUserId()).orElseThrow(() -> new IllegalArgumentException("Игрок не найден."));
        if (request.getCreatedAt().isBefore(requestsSince())) {
            request.setStatus(SquadJoinRequest.CANCELLED);
            joinRequestRepository.save(request);
            throw new IllegalStateException("Заявка устарела — попросите игрока подать новую.");
        }
        if (approve) {
            if (applicant.getSquadId() != null) {
                request.setStatus(SquadJoinRequest.CANCELLED);
                joinRequestRepository.save(request);
                throw new IllegalStateException("Игрок уже вступил в другой отряд.");
            }
            join(applicant, squad.getId());
            request.setStatus(SquadJoinRequest.APPROVED);
        } else {
            request.setStatus(SquadJoinRequest.DECLINED);
        }
        joinRequestRepository.save(request);
        eventPublisher.publishEvent(new SquadJoinDecisionEvent(this, squad, applicant, approve));
        return request;
    }

    /** После вступления в любой отряд остальные заявки игрока снимаются. */
    private void cancelPendingRequests(AppUser user) {
        for (SquadJoinRequest r : joinRequestRepository.findAllByUserIdAndStatus(user.getId(), SquadJoinRequest.PENDING)) {
            r.setStatus(SquadJoinRequest.CANCELLED);
            joinRequestRepository.save(r);
        }
    }

    /** Вступление через каталог — только в отряд с открытым набором. */
    @Transactional
    public Squad joinOpen(AppUser user, Long squadId) {
        Squad squad = squadRepository.findById(squadId).orElseThrow(() -> new IllegalArgumentException("Отряд не найден."));
        if (!squad.isOpenRecruitment()) {
            throw new IllegalStateException("В этом отряде набор закрыт — вступить можно только по приглашению.");
        }
        return join(user, squadId);
    }

    // ── Командная цель недели и серия ───────────────────────────────────────────

    private static LocalDateTime currentWeekStart() {
        return LocalDate.now().with(DayOfWeek.MONDAY).atStartOfDay();
    }

    public static long goalTarget(long memberCount) {
        return Math.max(GOAL_MIN, GOAL_PER_MEMBER * memberCount);
    }

    /** Прогресс цели за текущую неделю (понедельник 00:00 — воскресенье). Для отрядов меньше GOAL_MIN_MEMBERS
     *  цель не действует (eligible=false) — в карточке подсказываем, сколько человек не хватает. */
    public GoalProgress goalProgress(Squad squad) {
        long members = memberCount(squad);
        LocalDateTime from = currentWeekStart();
        long done = submissionRepository.countApprovedBySquadBetween(squad.getId(), from, from.plusWeeks(1));
        long target = goalTarget(members);
        return new GoalProgress(members >= GOAL_MIN_MEMBERS, done, target, done >= target, GOAL_BONUS_PER_MEMBER);
    }

    /** Серия отряда: сколько дней подряд (до сегодня включительно, либо до вчера, если сегодня ещё не было)
     *  хотя бы один участник выполнил квест. */
    public int streakDays(Squad squad) {
        Set<LocalDate> days = new HashSet<>();
        for (LocalDateTime t : submissionRepository.findApprovedTimesBySquadSince(squad.getId(), LocalDateTime.now().minusDays(60))) {
            days.add(t.toLocalDate());
        }
        LocalDate day = LocalDate.now();
        if (!days.contains(day)) {
            day = day.minusDays(1);
        }
        int streak = 0;
        while (days.contains(day)) {
            streak++;
            day = day.minusDays(1);
        }
        return streak;
    }

    /** Понедельник 00:00 (WeeklyResetScheduler): подводит итоги прошедшей недели — всем участникам с хотя бы одним
     *  квестом начисляется GOAL_BONUS_PER_MEMBER, если отряд (от GOAL_MIN_MEMBERS человек) выполнил цель.
     *  lastGoalSettledWeek защищает от двойной выплаты при повторном запуске. */
    @Transactional
    public void settleWeeklyGoals() {
        LocalDateTime weekEnd = currentWeekStart();
        LocalDateTime weekStart = weekEnd.minusWeeks(1);
        for (Squad squad : squadRepository.findAll()) {
            if (!"ACTIVE".equals(squad.getStatus()) || weekStart.toLocalDate().equals(squad.getLastGoalSettledWeek())) {
                continue;
            }
            squad.setLastGoalSettledWeek(weekStart.toLocalDate());
            squadRepository.save(squad);
            long members = memberCount(squad);
            if (members < GOAL_MIN_MEMBERS) {
                continue;
            }
            long done = submissionRepository.countApprovedBySquadBetween(squad.getId(), weekStart, weekEnd);
            long target = goalTarget(members);
            if (done < target) {
                continue;
            }
            Set<Long> contributors = new HashSet<>(submissionRepository.findApprovedUserIdsBySquadBetween(squad.getId(), weekStart, weekEnd));
            List<AppUser> rewarded = getMembers(squad).stream().filter(m -> contributors.contains(m.getId())).toList();
            for (AppUser member : rewarded) {
                excTx.creditExc(member, GOAL_BONUS_PER_MEMBER, ExcTransactionService.SQUAD_GOAL,
                        "Цель недели выполнена: отряд «" + squad.getName() + "»");
            }
            if (!rewarded.isEmpty()) {
                eventPublisher.publishEvent(new SquadGoalReachedEvent(this, squad, rewarded, GOAL_BONUS_PER_MEMBER, done, target));
            }
            log.info("Squad goal reached: '{}' {}/{}, {} members rewarded x{} EXC", squad.getName(), done, target, rewarded.size(), GOAL_BONUS_PER_MEMBER);
        }
    }

    @Transactional
    public String refreshInviteCode(Squad squad) {
        squad.setInviteCode(generateInviteCode());
        squadRepository.save(squad);
        return squad.getInviteCode();
    }

    private String generateInviteCode() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    public record SquadRankEntry(Squad squad, long weeklyXp, long memberCount) {}
}
