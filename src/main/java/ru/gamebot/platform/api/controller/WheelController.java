package ru.gamebot.platform.api.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.WheelSpinLogRepository;
import ru.gamebot.platform.service.WheelService;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/wheel")
@RequiredArgsConstructor
public class WheelController {

    private final AppUserRepository appUserRepository;
    private final WheelSpinLogRepository wheelSpinLogRepository;
    private final WheelService wheelService;

    public record WheelStatusDto(int tickets, long spinsToday, int maxSpinsPerDay) {}

    public record SpinResponseDto(
            boolean success,
            String message,
            String type,
            long excAmount,
            String label,
            int newTickets,
            long spinsToday
    ) {}

    @GetMapping
    public ResponseEntity<WheelStatusDto> status(@AuthenticationPrincipal Long telegramId) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

        long spinsToday = wheelSpinLogRepository.countByUserSince(user, LocalDate.now().atStartOfDay());
        return ResponseEntity.ok(new WheelStatusDto(user.getTickets(), spinsToday, WheelService.MAX_SPINS_PER_DAY));
    }

    @PostMapping("/spin")
    public ResponseEntity<SpinResponseDto> spin(@AuthenticationPrincipal Long telegramId) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

        try {
            WheelService.SpinResult result = wheelService.spin(user);
            long spinsToday = wheelSpinLogRepository.countByUserSince(user, LocalDate.now().atStartOfDay());
            return ResponseEntity.ok(new SpinResponseDto(
                    true, "Удача!", result.type(), result.excAmount(), result.label(),
                    user.getTickets(), spinsToday
            ));
        } catch (IllegalArgumentException e) {
            long spinsToday = wheelSpinLogRepository.countByUserSince(user, LocalDate.now().atStartOfDay());
            return ResponseEntity.ok(new SpinResponseDto(
                    false, e.getMessage(), null, 0, null,
                    user.getTickets(), spinsToday
            ));
        }
    }
}
