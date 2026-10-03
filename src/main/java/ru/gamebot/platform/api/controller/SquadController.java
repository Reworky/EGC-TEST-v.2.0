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

    /** Каталог «Найти отряд»: отряды с открытым набором (по недельной активности). */
    @GetMapping("/catalog")
    public ResponseEntity<List<CatalogEntry>> catalog() {
        List<CatalogEntry> result = squadService.findOpenSquads().stream()
                .map(e -> new CatalogEntry(e.squad().getId(), e.squad().getName(), e.memberCount(), e.weeklyXp()))
                .toList();
        return ResponseEntity.ok(result);
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
            result.add(new LeaderboardEntry(i + 1, e.squad().getName(), e.weeklyXp(), e.memberCount()));
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
            result.add(new LeaderboardEntry(i + 1, e.squad().getName(), e.weeklyXp(), e.memberCount()));
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
                goal.bonusPerMember(), SquadService.GOAL_MIN_MEMBERS, squadService.streakDays(squad));
    }

    record SquadDto(Long id, String name, String inviteCode, String inviteLink, boolean isCaptain,
                    long weeklyXp, long weeklyBonusPoints, List<MemberDto> members,
                    boolean openRecruitment, boolean goalEligible, long goalDone, long goalTarget, boolean goalReached,
                    long goalBonus, int goalMinMembers, int streakDays) {}
    record CatalogEntry(Long id, String name, long memberCount, long weeklyXp) {}
    record JoinOpenRequest(Long squadId) {}
    record RecruitmentRequest(boolean open) {}
    record MemberDto(Long telegramId, String nickname, String levelName, long weeklyXp, boolean isCaptain) {}
    /** xp — недельный или общий XP в зависимости от эндпоинта (/leaderboard vs /leaderboard/overall). */
    record LeaderboardEntry(int rank, String name, long xp, long memberCount) {}
    record CreateRequest(String name) {}
    record JoinRequest(String code) {}
}
