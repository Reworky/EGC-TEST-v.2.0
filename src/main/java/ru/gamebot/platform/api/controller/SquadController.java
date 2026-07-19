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

    private SquadDto toDto(Squad squad, AppUser user, Long telegramId) {
        List<AppUser> members = squadService.getMembers(squad);
        long weeklyXp = members.stream().mapToLong(AppUser::getWeeklyXp).sum();
        boolean isCaptain = telegramId.equals(squad.getCaptainTelegramId());
        List<MemberDto> memberDtos = members.stream()
                .map(m -> new MemberDto(
                        m.getTelegramId(),
                        m.getNickname(),
                        userService.getLevelName(m.getXp()),
                        m.getWeeklyXp(),
                        m.getTelegramId().equals(squad.getCaptainTelegramId())))
                .toList();
        return new SquadDto(squad.getId(), squad.getName(), squad.getInviteCode(),
                isCaptain, weeklyXp, memberDtos);
    }

    record SquadDto(Long id, String name, String inviteCode, boolean isCaptain,
                    long weeklyXp, List<MemberDto> members) {}
    record MemberDto(Long telegramId, String nickname, String levelName, long weeklyXp, boolean isCaptain) {}
    record LeaderboardEntry(int rank, String name, long weeklyXp, long memberCount) {}
    record CreateRequest(String name) {}
    record JoinRequest(String code) {}
}
