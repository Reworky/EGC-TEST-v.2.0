package ru.gamebot.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Курс GRAM (TON) к рублю для карточек вывода/доната. Цепочка источников (2026-09-29, после того как
 *  CoinGecko начал периодически отдавать 403 "Request blocked" через их CloudFront и один зашитый
 *  fallback молча разошёлся с реальностью более чем в 2 раза - см. implementation_log): сначала
 *  CoinGecko (прямая пара TON/RUB), при сбое - Binance (TON/USDT) x официальный курс ЦБ РФ (USD/RUB),
 *  и только если оба источника недоступны - зашитое число как крайний рубеж. Первые два источника
 *  считаются "живыми" (isUsingFallback() == false), крайний рубеж - "запасным" (true). */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeRateService {

    private static final String COINGECKO_URL =
            "https://api.coingecko.com/api/v3/simple/price?ids=the-open-network&vs_currencies=rub";
    private static final String BINANCE_TON_USDT_URL = "https://api.binance.com/api/v3/ticker/price?symbol=TONUSDT";
    private static final String CBR_DAILY_URL = "https://www.cbr-xml-daily.ru/daily_json.js";

    // Крайний рубеж, если недоступны ОБА источника выше - грубая оценка, легко устаревает.
    // Обновлено 2026-09-29 (было 300, разошлось с реальным курсом более чем в 2 раза - инцидент найден
    // по жалобе на заявку В-153). Это снимок на дату обновления, а не константа "навсегда" - при новой
    // жалобе на курс сверить https://www.coingecko.com/en/coins/toncoin (тикер сейчас GRAM, ex-Toncoin).
    private static final BigDecimal HARDCODED_FALLBACK_RATE = BigDecimal.valueOf(134);

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);
    // Если оба источника упали разом - не ломиться в оба при КАЖДОМ вызове (карточка со списком заявок
    // вызывает getTonRubRate() на каждую заявку - без бэкоффа это до ~15 сек задержки на каждую при отказе).
    private static final Duration RETRY_BACKOFF = Duration.ofMinutes(1);

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .build();

    private BigDecimal cachedRate = null;
    private Instant cacheTime = Instant.EPOCH;
    private Instant nextAttemptAt = Instant.EPOCH;
    private boolean usingFallback = false;

    public synchronized BigDecimal getTonRubRate() {
        boolean stale = cachedRate == null || Instant.now().isAfter(cacheTime.plus(CACHE_TTL));
        boolean readyToRetry = Instant.now().isAfter(nextAttemptAt);
        if (stale && readyToRetry) {
            BigDecimal rate = fetchFromCoinGecko();
            String source = "CoinGecko";
            if (rate == null) {
                rate = fetchFromBinanceAndCbr();
                source = "Binance+CBR";
            }
            if (rate != null) {
                cachedRate = rate;
                cacheTime = Instant.now();
                usingFallback = false;
                nextAttemptAt = Instant.EPOCH;
                log.info("Exchange rate updated via {}: 1 TON = {} RUB", source, cachedRate);
            } else {
                nextAttemptAt = Instant.now().plus(RETRY_BACKOFF);
                if (cachedRate == null) {
                    cachedRate = HARDCODED_FALLBACK_RATE;
                    usingFallback = true;
                    log.warn("Both exchange rate sources (CoinGecko, Binance+CBR) failed, using hardcoded fallback: {} RUB", cachedRate);
                } else {
                    // Держим последний известный курс (живой или уже зашитый) до следующей попытки через
                    // RETRY_BACKOFF - не откатываемся сразу на HARDCODED_FALLBACK_RATE из-за одного сбоя.
                    log.warn("Both exchange rate sources failed, keeping last known rate {} RUB for up to {}", cachedRate, RETRY_BACKOFF);
                }
            }
        }
        return cachedRate;
    }

    private BigDecimal fetchFromCoinGecko() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(COINGECKO_URL))
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode rub = objectMapper.readTree(response.body()).path("the-open-network").path("rub");
            if (!rub.isNumber()) {
                throw new IllegalStateException("Unexpected CoinGecko response: " + response.body());
            }
            return rub.decimalValue();
        } catch (Exception e) {
            log.warn("Failed to fetch exchange rate from CoinGecko: {}", e.getMessage());
            return null;
        }
    }

    /** Резервный источник: TON/USDT с Binance x официальный курс USD/RUB Центробанка - оба открытые
     *  бесплатные API без ключа, ни разу не блокировали запросы бота (в отличие от CoinGecko). */
    private BigDecimal fetchFromBinanceAndCbr() {
        try {
            HttpRequest tonRequest = HttpRequest.newBuilder()
                    .uri(URI.create(BINANCE_TON_USDT_URL))
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> tonResponse = httpClient.send(tonRequest, HttpResponse.BodyHandlers.ofString());
            JsonNode priceNode = objectMapper.readTree(tonResponse.body()).path("price");
            if (priceNode.isMissingNode()) {
                throw new IllegalStateException("Unexpected Binance response: " + tonResponse.body());
            }
            BigDecimal tonUsdt = new BigDecimal(priceNode.asText());

            HttpRequest cbrRequest = HttpRequest.newBuilder()
                    .uri(URI.create(CBR_DAILY_URL))
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> cbrResponse = httpClient.send(cbrRequest, HttpResponse.BodyHandlers.ofString());
            JsonNode usdValue = objectMapper.readTree(cbrResponse.body()).path("Valute").path("USD").path("Value");
            if (!usdValue.isNumber()) {
                throw new IllegalStateException("Unexpected CBR response: " + cbrResponse.body());
            }

            return tonUsdt.multiply(usdValue.decimalValue()).setScale(2, RoundingMode.HALF_DOWN);
        } catch (Exception e) {
            log.warn("Failed to fetch exchange rate from Binance+CBR fallback: {}", e.getMessage());
            return null;
        }
    }

    public BigDecimal rubToTon(BigDecimal rubAmount) {
        return rubAmount.divide(getTonRubRate(), 2, RoundingMode.HALF_DOWN);
    }

    public boolean isUsingFallback() {
        return usingFallback;
    }
}
