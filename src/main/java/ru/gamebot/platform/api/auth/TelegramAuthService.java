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
            // secret_key = SHA256(bot_token) — Telegram Login Widget spec
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] secretKey = sha256.digest(props.getBotToken().getBytes(StandardCharsets.UTF_8));

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
            byte[] computedHash = mac.doFinal(dataCheckString.getBytes(StandardCharsets.UTF_8));

            String computedHex = bytesToHex(computedHash);
            return MessageDigest.isEqual(computedHex.getBytes(), receivedHash.getBytes());
        } catch (Exception e) {
            log.error("Telegram auth validation error", e);
            return false;
        }
    }

    /**
     * Validates Telegram Mini App initData.
     * Spec: https://core.telegram.org/bots/webapps#validating-data-received-via-the-mini-app
     * Returns telegramId if valid, null otherwise.
     */
    public Long validateMiniApp(String initData) {
        try {
            Map<String, String> params = new java.util.LinkedHashMap<>();
            for (String part : initData.split("&")) {
                int eq = part.indexOf('=');
                if (eq < 0) continue;
                String key = java.net.URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8);
                String val = java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
                params.put(key, val);
            }

            String receivedHash = params.remove("hash");
            if (receivedHash == null) return null;

            String dataCheckString = params.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("");

            // secret_key = HMAC_SHA256(key="WebAppData", data=bot_token)
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("WebAppData".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] secretKey = mac.doFinal(props.getBotToken().getBytes(StandardCharsets.UTF_8));

            mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
            byte[] computedHash = mac.doFinal(dataCheckString.getBytes(StandardCharsets.UTF_8));

            if (!MessageDigest.isEqual(bytesToHex(computedHash).getBytes(), receivedHash.getBytes())) {
                log.warn("Mini App initData hash mismatch");
                return null;
            }

            String userJson = params.get("user");
            if (userJson == null) return null;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(userJson);
            if (!m.find()) return null;
            return Long.parseLong(m.group(1));

        } catch (Exception e) {
            log.error("Mini App initData validation error", e);
            return null;
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
