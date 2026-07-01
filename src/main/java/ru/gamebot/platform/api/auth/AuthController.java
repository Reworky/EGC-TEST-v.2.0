package ru.gamebot.platform.api.auth;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.gamebot.platform.domain.repository.AppUserRepository;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final TelegramAuthService telegramAuthService;
    private final JwtService jwtService;
    private final AppUserRepository appUserRepository;

    /**
     * POST /api/auth/telegram
     * Body: { id, first_name, username, photo_url, auth_date, hash }
     * Returns: { token, registered }
     */
    @PostMapping("/telegram")
    public ResponseEntity<?> telegramLogin(@RequestBody Map<String, String> data) {
        if (!telegramAuthService.validate(data)) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid Telegram auth data"));
        }

        Long telegramId = Long.parseLong(data.get("id"));
        boolean registered = appUserRepository.findByTelegramId(telegramId)
                .map(u -> u.isRegistrationCompleted())
                .orElse(false);

        String token = jwtService.generate(telegramId);
        return ResponseEntity.ok(Map.of(
                "token", token,
                "registered", registered
        ));
    }
}
