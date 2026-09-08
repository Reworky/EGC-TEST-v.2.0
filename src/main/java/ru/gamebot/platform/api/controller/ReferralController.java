package ru.gamebot.platform.api.controller;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.gamebot.platform.api.dto.ReferralDto;
import ru.gamebot.platform.api.dto.ReferralRankingDto;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.service.ExcTransactionService;
import ru.gamebot.platform.service.ReferralBoostService;
import ru.gamebot.platform.service.UserService;

@RestController
@RequestMapping("/api/profile/referrals")
@RequiredArgsConstructor
public class ReferralController {

    private static final long[] MILESTONES = {3_000, 10_000, 30_000, 100_000};
    private static final int TOP_N = 5;

    private final AppUserRepository appUserRepository;
    private final ExcTransactionService excTransactionService;
    private final ReferralBoostService referralBoostService;
    private final UserService userService;

    @GetMapping
    public ResponseEntity<ReferralDto> referrals(@AuthenticationPrincipal Long telegramId) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        long earned = user.getReferralEarnedExc();
        long nextMilestone = MILESTONES[MILESTONES.length - 1];
        for (long m : MILESTONES) {
            if (earned < m) { nextMilestone = m; break; }
        }
        int progressPercent = (int) Math.min(100, earned * 100 / nextMilestone);

        var activeBoost = referralBoostService.findActiveBoost();

        Integer nextFriendMilestone = userService.nextInvitedFriendsMilestone(user.getInvitedFriends());
        int friendProgressPercent = nextFriendMilestone == null
                ? 100
                : (int) Math.min(100, user.getInvitedFriends() * 100L / nextFriendMilestone);

        return ResponseEntity.ok(ReferralDto.builder()
                .referralLink(userService.buildReferralLink(user))
                .shareUrl(userService.buildShareUrl(user))
                .invitedFriends(user.getInvitedFriends())
                .earnedExc(earned)
                .nextMilestone(nextMilestone)
                .progressPercent(progressPercent)
                .boostActive(activeBoost.isPresent())
                .boostMultiplier(activeBoost.map(b -> b.getMultiplier()).orElse(null))
                .boostEndsAt(activeBoost.map(b -> b.getEndAt().toString()).orElse(null))
                .currentFriendBadge(userService.currentInvitedFriendsBadge(user.getInvitedFriends()).orElse(null))
                .nextFriendMilestone(nextFriendMilestone)
                .friendProgressPercent(friendProgressPercent)
                .build());
    }

    @GetMapping("/ranking")
    public ResponseEntity<ReferralRankingDto> ranking(@AuthenticationPrincipal Long telegramId) {
        AppUser me = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (me == null) {
            return ResponseEntity.notFound().build();
        }

        LocalDateTime weekStart = LocalDate.now().with(DayOfWeek.MONDAY).atStartOfDay();
        List<Object[]> rows = excTransactionService.findReferralEarningsRankingBetween(weekStart, LocalDateTime.now());

        List<ReferralRankingDto.Entry> top = new ArrayList<>();
        ReferralRankingDto.Entry yourEntry = null;

        for (int i = 0; i < rows.size(); i++) {
            Long userId = (Long) rows.get(i)[0];
            long weeklyExc = ((Number) rows.get(i)[1]).longValue();
            boolean isMe = userId.equals(me.getId());

            if (i < TOP_N) {
                AppUser rowUser = isMe ? me : appUserRepository.findById(userId).orElse(null);
                top.add(ReferralRankingDto.Entry.builder()
                        .rank(i + 1)
                        .nickname(rowUser != null ? rowUser.getNickname() : "Игрок")
                        .invitedFriends(rowUser != null ? rowUser.getInvitedFriends() : 0)
                        .weeklyExc(weeklyExc)
                        .isMe(isMe)
                        .build());
            } else if (isMe) {
                yourEntry = ReferralRankingDto.Entry.builder()
                        .rank(i + 1)
                        .nickname(me.getNickname())
                        .invitedFriends(me.getInvitedFriends())
                        .weeklyExc(weeklyExc)
                        .isMe(true)
                        .build();
                break;
            }
        }

        return ResponseEntity.ok(ReferralRankingDto.builder().top(top).yourEntry(yourEntry).build());
    }
}
