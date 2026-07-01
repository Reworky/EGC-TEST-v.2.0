package ru.gamebot.platform.api.controller;

import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.gamebot.platform.api.dto.RewardRequestDto;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.service.RewardService;

@RestController
@RequestMapping("/api/profile/rewards")
@RequiredArgsConstructor
public class RewardHistoryController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final AppUserRepository appUserRepository;
    private final RewardService rewardService;

    @GetMapping
    public ResponseEntity<?> rewards(@AuthenticationPrincipal Long telegramId) {
        return appUserRepository.findByTelegramId(telegramId)
                .filter(u -> u.isRegistrationCompleted())
                .map(user -> {
                    List<RewardRequestDto> list = rewardService.findUserRequests(user).stream()
                            .map(r -> RewardRequestDto.builder()
                                    .id(r.getId())
                                    .rewardTitle(r.getRewardItem().getTitle())
                                    .category(r.getRewardItem().getCategory())
                                    .priceCoins(r.getRewardItem().getPriceCoins())
                                    .status(r.getStatus().name())
                                    .adminComment(r.getAdminComment())
                                    .createdAt(r.getCreatedAt() != null ? r.getCreatedAt().format(FMT) : null)
                                    .build())
                            .toList();
                    return ResponseEntity.ok(list);
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
