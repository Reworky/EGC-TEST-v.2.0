package ru.gamebot.platform.api.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.config.AppProperties;

@Service
@RequiredArgsConstructor
public class JwtService {

    private final AppProperties props;

    public String generate(Long telegramId) {
        SecretKey key = signingKey();
        long expiryMs = props.getJwtExpirationDays() * 24 * 60 * 60 * 1000L;
        return Jwts.builder()
                .subject(String.valueOf(telegramId))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiryMs))
                .signWith(key)
                .compact();
    }

    public Long extractTelegramId(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return Long.parseLong(claims.getSubject());
    }

    public boolean isValid(String token) {
        try {
            extractTelegramId(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(props.getJwtSecret().getBytes(StandardCharsets.UTF_8));
    }
}
