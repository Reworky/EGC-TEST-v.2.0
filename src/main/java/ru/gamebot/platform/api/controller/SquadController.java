package ru.gamebot.platform.api.controller;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Squad;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.service.SquadService;
import ru.gamebot.platform.service.UserService;

@RestController
@RequestMapping("/api/squads")
@RequiredArgsConstructor
public class SquadController {

    private final SquadService squadService;
    private final AppUserRepository appUserRepository;
    private final UserService userService;
    private final ru.gamebot.platform.service.TelegramFileService telegramFileService;

    private AppUser getUser(Long telegramId) {
        return appUserRepository.findByTelegramId(telegramId).orElseThrow();
    }

    @GetMapping("/me")
    public ResponseEntity<SquadDto> mySquad(@AuthenticationPrincipal Long telegramId) {
        AppUser user = getUser(telegramId);
        Optional<Squad> squad = squadService.findByUser(user);
        if (squad.isEmpty()) return ResponseEntity.ok(null);
        return ResponseEntity.ok(toDto(squad.get(), user, telegramId));
    }

    @PostMapping("/create")
    public ResponseEntity<SquadDto> create(@AuthenticationPrincipal Long telegramId,
                                           @RequestBody CreateRequest body) {
        AppUser user = getUser(telegramId);
        if (user.getSquadId() != null) {
            return ResponseEntity.badRequest().build();
        }
        Squad squad = squadService.create(user, body.name());
        return ResponseEntity.ok(toDto(squad, user, telegramId));
    }

    @PostMapping("/join")
    public ResponseEntity<SquadDto> join(@AuthenticationPrincipal Long telegramId,
                                         @RequestBody JoinRequest body) {
        AppUser user = getUser(telegramId);
        Squad squad = squadService.joinByInviteCode(user, body.code());
        return ResponseEntity.ok(toDto(squad, user, telegramId));
    }

    /** Картинка флага отряда (любой активный отряд — флаг показывается в публичных карточках). */
    @GetMapping("/flag/{squadId}")
    public ResponseEntity<byte[]> flag(@PathVariable Long squadId) {
        Optional<Squad> squad = squadService.findById(squadId);
        if (squad.isEmpty() || squad.get().getFlagFileId() == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] image = telegramFileService.downloadFile(squad.get().getFlagFileId());
            return ResponseEntity.ok()
                    .contentType(org.springframework.http.MediaType.IMAGE_JPEG)
                    .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofHours(1)))
                    .body(image);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.notFound().build();
        } catch (java.io.IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** Каталог «Найти отряд»: все активные отряды (открытые — вступить сразу, закрытые — по заявке), по недельной
     *  активности; q — часть названия для поиска. */
    @GetMapping("/catalog")
    public ResponseEntity<List<CatalogEntry>> catalog(@AuthenticationPrincipal Long telegramId,
                                                      @RequestParam(required = false) String q) {
        AppUser user = getUser(telegramId);
        List<CatalogEntry> result = squadService.findCatalog(q).stream()
                .limit(30)
                .map(e -> new CatalogEntry(e.squad().getId(), e.squad().getName(), e.memberCount(), e.weeklyXp(),
                        e.squad().isOpenRecruitment(), squadService.hasPendingRequest(user, e.squad().getId())))
                .toList();
        return ResponseEntity.ok(result);
    }

    /** Публичная карточка любого отряда (из рейтинга/каталога): состав-лидеры, активность, можно ли вступить. */
    @GetMapping("/view/{squadId}")
    public ResponseEntity<?> view(@AuthenticationPrincipal Long telegramId, @PathVariable Long squadId) {
        AppUser user = getUser(telegramId);
        Optional<Squad> found = squadService.findById(squadId);
        if (found.isEmpty() || !"ACTIVE".equals(found.get().getStatus())) {
            return ResponseEntity.notFound().build();
        }
        Squad squad = found.get();
        List<AppUser> members = squadService.getMembers(squad);
        List<TopMemberDto> top = members.stream()
                .sorted(java.util.Comparator.comparingLong(AppUser::getWeeklyXp).reversed())
                .limit(5)
                .map(m -> new TopMemberDto(m.getNickname(), userService.getLevelName(m.getXp()), m.getWeeklyXp(),
                        m.getTelegramId().equals(squad.getCaptainTelegramId())))
                .toList();
        SquadService.GoalProgress goal = squadService.goalProgress(squad);
        boolean mine = squadId.equals(user.getSquadId());
        return ResponseEntity.ok(new PublicSquadDto(squad.getId(), squad.getName(), members.size(),
                squadService.squadWeeklyXp(squad), squad.isOpenRecruitment(),
                squadService.hasPendingRequest(user, squadId), mine, user.getSquadId() != null,
                squadService.streakDays(squad), goal.eligible(), goal.done(), goal.target(), top,
                squad.getFlagFileId() != null));
    }

    /** Заявка в отряд с закрытым набором. */
    @PostMapping("/request")
    public ResponseEntity<?> requestJoin(@AuthenticationPrincipal Long telegramId, @RequestBody JoinOpenRequest body) {
        AppUser user = getUser(telegramId);
        try {
            squadService.requestJoin(user, body.squadId());
            return ResponseEntity.ok(java.util.Map.of("success", true));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", e.getMessage()));
        }
    }

    /** Живые заявки в отряд капитана. */
    @GetMapping("/requests")
    public ResponseEntity<List<RequestDto>> requests(@AuthenticationPrincipal Long telegramId) {
        AppUser user = getUser(telegramId);
        Optional<Squad> squad = squadService.findByUser(user);
        if (squad.isEmpty() || !telegramId.equals(squad.get().getCaptainTelegramId())) {
            return ResponseEntity.ok(List.of());
        }
        List<RequestDto> result = squadService.pendingRequests(squad.get()).stream()
                .map(r -> squadService.findApplicant(r)
                        .map(a -> new RequestDto(r.getId(), a.getNickname(), userService.getLevelName(a.getXp()), a.getXp()))
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/requests/{requestId}/accept")
    public ResponseEntity<?> acceptRequest(@AuthenticationPrincipal Long telegramId, @PathVariable Long requestId) {
        return decide(telegramId, requestId, true);
    }

    @PostMapping("/requests/{requestId}/decline")
    public ResponseEntity<?> declineRequest(@AuthenticationPrincipal Long telegramId, @PathVariable Long requestId) {
        return decide(telegramId, requestId, false);
    }

    private ResponseEntity<?> decide(Long telegramId, Long requestId, boolean approve) {
        AppUser user = getUser(telegramId);
        try {
            squadService.decideRequest(user, requestId, approve);
            return ResponseEntity.ok(java.util.Map.of("success", true));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", e.getMessage()));
        }
    }

    /** Вступление через каталог — только в отряд с открытым набором. */
    @PostMapping("/join-open")
    public ResponseEntity<?> joinOpen(@AuthenticationPrincipal Long telegramId, @RequestBody JoinOpenRequest body) {
        AppUser user = getUser(telegramId);
        try {
            Squad squad = squadService.joinOpen(user, body.squadId());
            return ResponseEntity.ok(toDto(squad, user, telegramId));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", e.getMessage()));
        }
    }

    /** Капитан открывает/закрывает набор в отряд. */
    @PostMapping("/recruitment")
    public ResponseEntity<?> recruitment(@AuthenticationPrincipal Long telegramId, @RequestBody RecruitmentRequest body) {
        AppUser user = getUser(telegramId);
        try {
            Squad squad = squadService.setRecruitment(user, body.open());
            return ResponseEntity.ok(toDto(squad, user, telegramId));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/leave")
    public ResponseEntity<Void> leave(@AuthenticationPrincipal Long telegramId) {
        AppUser user = getUser(telegramId);
        squadService.leave(user);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/kick/{memberTelegramId}")
    public ResponseEntity<Void> kick(@AuthenticationPrincipal Long telegramId,
                                     @PathVariable Long memberTelegramId) {
        AppUser user = getUser(telegramId);
        squadService.kick(user, memberTelegramId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/disband")
    public ResponseEntity<Void> disband(@AuthenticationPrincipal Long telegramId) {
        AppUser user = getUser(telegramId);
        squadService.disband(user);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/leaderboard")
    public ResponseEntity<List<LeaderboardEntry>> leaderboard() {
        List<SquadService.SquadRankEntry> entries = squadService.getLeaderboard();
        List<LeaderboardEntry> result = new java.util.ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            SquadService.SquadRankEntry e = entries.get(i);
            result.add(new LeaderboardEntry(i + 1, e.squad().getName(), e.weeklyXp(), e.memberCount(), e.squad().getId()));
        }
        return ResponseEntity.ok(result);
    }

    /** Общий (не сбрасывающийся) рейтинг — по сумме постоянного XP участников, см. SquadService.getOverallLeaderboard. */
    @GetMapping("/leaderboard/overall")
    public ResponseEntity<List<LeaderboardEntry>> overallLeaderboard() {
        List<SquadService.SquadRankEntry> entries = squadService.getOverallLeaderboard();
        List<LeaderboardEntry> result = new java.util.ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            SquadService.SquadRankEntry e = entries.get(i);
            result.add(new LeaderboardEntry(i + 1, e.squad().getName(), e.weeklyXp(), e.memberCount(), e.squad().getId()));
        }
        return ResponseEntity.ok(result);
    }

    private SquadDto toDto(Squad squad, AppUser user, Long telegramId) {
        List<AppUser> members = squadService.getMembers(squad);
        // squadService.squadWeeklyXp() — не сумма по участникам напрямую, т.к. включает ещё
        // weeklyBonusPoints (реферал вступил в отряд пригласившего, см. Squad.java) — раньше здесь
        // считали сумму по members напрямую, бонус был не виден в Mini App (тот же баг чинили в боте).
        long weeklyXp = squadService.squadWeeklyXp(squad);
        boolean isCaptain = telegramId.equals(squad.getCaptainTelegramId());
        List<MemberDto> memberDtos = members.stream()
                .map(m -> new MemberDto(
                        m.getTelegramId(),
                        m.getNickname(),
                        userService.getLevelName(m.getXp()),
                        m.getWeeklyXp(),
                        m.getTelegramId().equals(squad.getCaptainTelegramId())))
                .toList();
        // Готовая ссылка для "Поделиться" — единая точка построения (buildReferralLink), та же,
        // что использует бот, чтобы мини-апп не собирал свой формат ссылки заново (см. UserService).
        String inviteLink = userService.buildReferralLink(user);
        SquadService.GoalProgress goal = squadService.goalProgress(squad);
        return new SquadDto(squad.getId(), squad.getName(), squad.getInviteCode(), inviteLink,
                isCaptain, weeklyXp, squad.getWeeklyBonusPoints(), memberDtos,
                squad.isOpenRecruitment(), goal.eligible(), goal.done(), goal.target(), goal.reached(),
                goal.bonusPerMember(), SquadService.GOAL_MIN_MEMBERS, squadService.streakDays(squad),
                isCaptain ? squadService.pendingRequestCount(squad) : 0,
                squad.getFlagFileId() != null);
    }

    record SquadDto(Long id, String name, String inviteCode, String inviteLink, boolean isCaptain,
                    long weeklyXp, long weeklyBonusPoints, List<MemberDto> members,
                    boolean openRecruitment, boolean goalEligible, long goalDone, long goalTarget, boolean goalReached,
                    long goalBonus, int goalMinMembers, int streakDays, long pendingRequests,
                    boolean hasFlag) {}
    record CatalogEntry(Long id, String name, long memberCount, long weeklyXp, boolean open, boolean requested) {}
    record RequestDto(Long id, String nickname, String levelName, long xp) {}
    record TopMemberDto(String nickname, String levelName, long weeklyXp, boolean isCaptain) {}
    record PublicSquadDto(Long id, String name, int memberCount, long weeklyXp, boolean open, boolean requested,
                          boolean mine, boolean viewerHasSquad, int streakDays, boolean goalEligible, long goalDone,
                          long goalTarget, List<TopMemberDto> topMembers, boolean hasFlag) {}
    record JoinOpenRequest(Long squadId) {}
    record RecruitmentRequest(boolean open) {}
    record MemberDto(Long telegramId, String nickname, String levelName, long weeklyXp, boolean isCaptain) {}
    /** xp — недельный или общий XP в зависимости от эндпоинта (/leaderboard vs /leaderboard/overall). */
    record LeaderboardEntry(int rank, String name, long xp, long memberCount, Long squadId) {}
    record CreateRequest(String name) {}
    record JoinRequest(String code) {}
}
