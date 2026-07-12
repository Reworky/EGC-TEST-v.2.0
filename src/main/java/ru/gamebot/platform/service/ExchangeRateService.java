package ru.gamebot.platform.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ExchangeRateService {

    private static final String URL =
            "https://api.coingecko.com/api/v3/simple/price?ids=the-open-network&vs_currencies=rub";
    // Грубая оценка на случай недоступности API — курс TON гораздо волатильнее USDT, при живом API не используется.
    private static final BigDecimal FALLBACK_RATE = BigDecimal.valueOf(300);
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .build();

    private BigDecimal cachedRate = null;
    private Instant cacheTime = Instant.EPOCH;
    private boolean usingFallback = false;

    public synchronized BigDecimal getTonRubRate() {
        if (cachedRate == null || Instant.now().isAfter(cacheTime.plus(CACHE_TTL))) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(URL))
                        .timeout(HTTP_TIMEOUT)
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                String body = response.body();
                // Parse: {"the-open-network":{"rub":320.5}}
                int idx = body.indexOf("\"rub\":");
                if (idx < 0) throw new IllegalStateException("Unexpected response: " + body);
                String after = body.substring(idx + 6).replaceAll("[^0-9.]", "");
                int end = after.indexOf('}');
                String numStr = end > 0 ? after.substring(0, end) : after;
                cachedRate = new BigDecimal(numStr.trim());
                cacheTime = Instant.now();
                usingFallback = false;
                log.info("Exchange rate updated: 1 TON = {} RUB", cachedRate);
            } catch (Exception e) {
                log.warn("Failed to fetch exchange rate: {}", e.getMessage());
                if (cachedRate == null) {
                    cachedRate = FALLBACK_RATE;
                    usingFallback = true;
                }
            }
        }
        return cachedRate;
    }

    public BigDecimal rubToTon(BigDecimal rubAmount) {
        return rubAmount.divide(getTonRubRate(), 2, RoundingMode.HALF_DOWN);
    }

    public boolean isUsingFallback() {
        return usingFallback;
    }
}
