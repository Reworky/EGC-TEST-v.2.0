package ru.gamebot.platform.api.controller;

import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.gamebot.platform.api.dto.RewardRequestDto;
import ru.gamebot.platform.api.dto.ShopActionResponseDto;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.RewardRequest;
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
                .filter(AppUser::isRegistrationCompleted)
                .map(user -> {
                    // Заявки на вывод EXC показываются отдельно на странице Кошелька — см. WalletController.
                    List<RewardRequestDto> list = rewardService.findUserRequests(user).stream()
                            .filter(r -> !"Вывод".equals(r.getRewardItem().getCategory()))
                            .map(this::toDto)
                            .toList();
                    return ResponseEntity.ok(list);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<ShopActionResponseDto> cancel(@PathVariable Long id, @AuthenticationPrincipal Long telegramId) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND).build();
        }
        try {
            rewardService.cancelRequest(id, user);
            return ResponseEntity.ok(ShopActionResponseDto.builder()
                    .success(true)
                    .message("Заявка отменена, EXC возвращены на баланс.")
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ShopActionResponseDto.builder().success(false).message(e.getMessage()).build());
        }
    }

    private RewardRequestDto toDto(RewardRequest r) {
        return RewardRequestDto.builder()
                .id(r.getId())
                .displayId(r.getDisplayId() != null ? r.getDisplayId() : r.getId())
                .rewardTitle(r.getRewardItem().getTitle())
                .category(r.getRewardItem().getCategory())
                .priceCoins(r.getRewardItem().getPriceCoins())
                .status(r.getStatus().name())
                .adminComment(r.getAdminComment())
                .createdAt(r.getCreatedAt() != null ? r.getCreatedAt().format(FMT) : null)
                .build();
    }
}
