package ru.gamebot.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Клиент Crypto Pay API (@CryptoBot): счета на оплату тарифа в рублях с оплатой TON/USDT и проверка статуса. Токен приложения - VPN2_CRYPTOBOT_TOKEN
 *  (в @CryptoBot: Crypto Pay → Create App); пока токен не задан, способ закрыт. Оплату находим опросом статусов (webhook не нужен). */
@Slf4j
@Component
public class CryptoPayClient {

    public record Invoice(long invoiceId, String status, String payUrl) {}

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final ObjectMapper objectMapper;
    private final String token;
    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public CryptoPayClient(ObjectMapper objectMapper,
                           @Value("${app.vpn2-cryptobot-token:}") String token,
                           @Value("${app.vpn2-cryptobot-url:https://pay.crypt.bot/api}") String baseUrl) {
        this.objectMapper = objectMapper;
        this.token = token == null ? "" : token.trim();
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://pay.crypt.bot/api" : baseUrl.trim().replaceAll("/+$", "");
    }

    public boolean isConfigured() {
        return !token.isBlank();
    }

    /** Счёт на сумму в рублях (оплата TON или USDT по курсу CryptoBot). Срок жизни - expiresInSec секунд. */
    public Invoice createInvoice(int amountRub, String description, String payload, int expiresInSec) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("currency_type", "fiat");
        body.put("fiat", "RUB");
        body.put("amount", String.valueOf(amountRub));
        body.put("accepted_assets", "TON,USDT");
        body.put("description", description);
        body.put("payload", payload);
        body.put("allow_comments", false);
        body.put("allow_anonymous", false);
        body.put("expires_in", expiresInSec);
        JsonNode res = call("createInvoice", body);
        JsonNode r = res.path("result");
        return new Invoice(r.path("invoice_id").asLong(0), r.path("status").asText(""), r.path("bot_invoice_url").asText(""));
    }

    /** Статусы счетов: invoice_id -> active | paid | expired. Не найденные в ответе счета пропускаются. */
    public Map<Long, String> statuses(List<Long> invoiceIds) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("invoice_ids", invoiceIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
        JsonNode res = call("getInvoices", body);
        Map<Long, String> out = new HashMap<>();
        for (JsonNode item : res.path("result").path("items")) {
            out.put(item.path("invoice_id").asLong(0), item.path("status").asText(""));
        }
        return out;
    }

    private JsonNode call(String method, Map<String, Object> body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + method))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Crypto-Pay-API-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(response.body());
        if (response.statusCode() != 200 || !root.path("ok").asBoolean(false)) {
            // в лог идёт только имя метода и ответ API: токен в заголовке и в лог не попадает
            throw new IOException("Crypto Pay " + method + ": HTTP " + response.statusCode() + " " + root.path("error").toString());
        }
        return root;
    }
}
