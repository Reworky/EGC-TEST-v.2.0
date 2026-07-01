package ru.gamebot.platform.api.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.config.AppProperties;

/**
 * Validates the data payload from Telegram Login Widget.
 * Spec: https://core.telegram.org/widgets/login#checking-authorization
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramAuthService {

    private static final long AUTH_MAX_AGE_SECONDS = 86_400; // 24 hours

    private final AppProperties props;

    public boolean validate(Map<String, String> data) {
        String receivedHash = data.get("hash");
        if (receivedHash == null) {
            return false;
        }

        String authDateStr = data.get("auth_date");
        if (authDateStr == null) {
            return false;
        }
        long authDate = Long.parseLong(authDateStr);
        if (Instant.now().getEpochSecond() - authDate > AUTH_MAX_AGE_SECONDS) {
            log.warn("Telegram auth_date expired: {}", authDate);
            return false;
        }

        // Build data-check-string: sorted key=value pairs (excluding hash), joined by \n
        TreeMap<String, String> sorted = new TreeMap<>(data);
        sorted.remove("hash");
        String dataCheckString = sorted.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");

        try {
            // secret_key = HMAC-SHA256(bot_token, "WebAppData")
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("WebAppData".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] secretKey = mac.doFinal(props.getBotToken().getBytes(StandardCharsets.UTF_8));

            mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
            byte[] computedHash = mac.doFinal(dataCheckString.getBytes(StandardCharsets.UTF_8));

            String computedHex = bytesToHex(computedHash);
            return MessageDigest.isEqual(computedHex.getBytes(), receivedHash.getBytes());
        } catch (Exception e) {
            log.error("Telegram auth validation error", e);
            return false;
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
