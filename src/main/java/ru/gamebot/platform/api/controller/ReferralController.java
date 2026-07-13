package ru.gamebot.platform.api.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.gamebot.platform.api.dto.ReferralDto;
import ru.gamebot.platform.config.AppProperties;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.repository.AppUserRepository;

@RestController
@RequestMapping("/api/profile/referrals")
@RequiredArgsConstructor
public class ReferralController {

    private static final long[] MILESTONES = {3_000, 10_000, 30_000, 100_000};

    private final AppUserRepository appUserRepository;
    private final AppProperties appProperties;

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

        return ResponseEntity.ok(ReferralDto.builder()
                .referralLink("https://t.me/" + appProperties.getBotUsername() + "?start=ref_" + user.getTelegramId())
                .invitedFriends(user.getInvitedFriends())
                .earnedExc(earned)
                .nextMilestone(nextMilestone)
                .progressPercent(progressPercent)
                .build());
    }
}
