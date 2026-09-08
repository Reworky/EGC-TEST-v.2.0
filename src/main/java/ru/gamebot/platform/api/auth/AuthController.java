package ru.gamebot.platform.api.auth;

import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.service.UserService;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    /** Ручной рубильник техработ мини-аппа (2026-09-07) — доступ временно оставлен только
     *  указанным никам для тестирования, остальные видят "Технические работы". Пользуются ботом
     *  как обычно, это не затрагивает GamePlatformBot вообще. Снять — вернуть MAINTENANCE в false. */
    private static final boolean MINI_APP_MAINTENANCE = true;
    private static final Set<String> MAINTENANCE_ALLOWLIST = Set.of("brokengame");

    private final TelegramAuthService telegramAuthService;
    private final JwtService jwtService;
    private final AppUserRepository appUserRepository;
    private final UserService userService;

    /**
     * POST /api/auth/telegram
     * Body: { id, first_name, username, photo_url, auth_date, hash }
     * Returns: { token, registered }
     */
    @PostMapping("/telegram")
    public ResponseEntity<?> telegramLogin(@RequestBody Map<String, Object> rawData) {
        Map<String, String> data = new java.util.HashMap<>();
        rawData.forEach((k, v) -> data.put(k, String.valueOf(v)));

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

    /**
     * POST /api/auth/miniapp
     * Body: { initData: "<raw Telegram.WebApp.initData string>" }
     * Returns: { token, registered }
     * Validates initData using HMAC-SHA256 with key = HMAC_SHA256("WebAppData", bot_token)
     */
    @PostMapping("/miniapp")
    public ResponseEntity<?> miniAppLogin(@RequestBody Map<String, String> body) {
        String initData = body.get("initData");
        if (initData == null || initData.isBlank()) {
            return ResponseEntity.status(400).body(Map.of("error", "initData is required"));
        }

        Long telegramId = telegramAuthService.validateMiniApp(initData);
        if (telegramId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid initData"));
        }

        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (MINI_APP_MAINTENANCE) {
            String nickname = user != null ? user.getNickname() : null;
            boolean allowed = nickname != null && MAINTENANCE_ALLOWLIST.contains(nickname.toLowerCase());
            if (!allowed) {
                return ResponseEntity.status(503).body(Map.of("maintenance", true));
            }
        }
        userService.touchMiniAppOpen(telegramId);

        boolean registered = user != null && user.isRegistrationCompleted();

        String token = jwtService.generate(telegramId);
        return ResponseEntity.ok(Map.of(
                "token", token,
                "registered", registered,
                "telegramId", telegramId
        ));
    }
}
