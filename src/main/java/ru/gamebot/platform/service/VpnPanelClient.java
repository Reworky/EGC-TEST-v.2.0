package ru.gamebot.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Клиент API панели 3x-ui (inbound hysteria, клиенты EGC VPN). Новая версия панели: вход с CSRF-токеном из meta страницы,
 *  клиенты - отдельный раздел /panel/api/clients/*. Сессия живёт в cookie; при ответе не-JSON/не-200 перелогиниваемся и повторяем один раз.
 *  TLS проверяется штатно (у панели сертификат Let's Encrypt на IP; если он перестанет обновляться, запросы начнут падать с алертом админам).
 *  Форматы update/del проверены на живой панели 02.11 (scripts/vpn_3xui_probe.py ext/off). */
@Slf4j
@Component
public class VpnPanelClient {

    public static class PanelException extends Exception {
        public PanelException(String message) { super(message); }
        public PanelException(String message, Throwable cause) { super(message, cause); }
    }

    /** Данные клиента для панели; totalBytes = 0 - без лимита трафика. */
    public record ClientSpec(String email, String subId, String uuid, String auth, String password,
                              long totalBytes, long expiryMs, int limitIp, boolean enable, String comment) {}

    private static final Pattern CSRF = Pattern.compile("name=\"csrf-token\" content=\"([^\"]+)\"");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String user;
    private final String pass;
    private final int inboundId;
    private final HttpClient httpClient;

    private String csrf = "";
    private boolean loggedIn = false;

    public VpnPanelClient(ObjectMapper objectMapper,
                          @Value("${app.vpn-xui-url:}") String url,
                          @Value("${app.vpn-xui-user:}") String user,
                          @Value("${app.vpn-xui-pass:}") String pass,
                          @Value("${app.vpn-xui-inbound-id:9}") int inboundId) {
        this.objectMapper = objectMapper;
        this.baseUrl = url == null ? "" : url.trim().replaceAll("/+$", "");
        this.user = user == null ? "" : user;
        this.pass = pass == null ? "" : pass;
        this.inboundId = inboundId;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .cookieHandler(new CookieManager())
                .build();
    }

    public boolean isConfigured() {
        return !baseUrl.isBlank() && !user.isBlank() && !pass.isBlank();
    }

    /** Клиент по метке (email) вместе со своим числовым id панели и счётчиками трафика; пусто - такого клиента нет. */
    public synchronized Optional<JsonNode> findClient(String email) throws PanelException {
        JsonNode res = api("/panel/api/clients/list", null);
        if (!res.path("success").asBoolean(false)) {
            throw new PanelException("список клиентов: " + res.path("msg").asText());
        }
        for (JsonNode n : res.path("obj")) {
            if (email.equals(n.path("email").asText())) {
                return Optional.of(n);
            }
        }
        return Optional.empty();
    }

    /** Состояние клиента в панели: расход и лимит трафика (байты), включён ли, последнее подключение и последняя загрузка подписки приложением (мс, 0 - не было). */
    public record ClientStat(long used, long total, boolean enable, long lastOnlineMs, long lastSubFetchMs) {}

    /** Состояние всех клиентов одним запросом: метка -> ClientStat. */
    public synchronized Map<String, ClientStat> clientStats() throws PanelException {
        JsonNode res = api("/panel/api/clients/list", null);
        if (!res.path("success").asBoolean(false)) {
            throw new PanelException("список клиентов: " + res.path("msg").asText());
        }
        Map<String, ClientStat> out = new HashMap<>();
        for (JsonNode n : res.path("obj")) {
            JsonNode t = n.path("traffic");
            out.put(n.path("email").asText(), new ClientStat(t.path("up").asLong(0) + t.path("down").asLong(0), n.path("totalGB").asLong(0),
                    n.path("enable").asBoolean(true), t.path("lastOnline").asLong(0), t.path("lastSubFetch").asLong(0)));
        }
        return out;
    }

    /** Создаёт клиента или, если такая метка уже есть, обновляет срок/лимиты (повторный вызов безопасен). */
    public synchronized void upsertClient(ClientSpec spec) throws PanelException {
        Optional<JsonNode> existing = findClient(spec.email());
        if (existing.isEmpty()) {
            ObjectNode body = objectMapper.createObjectNode();
            body.set("client", clientNode(spec));
            body.putArray("inboundIds").add(inboundId);
            requireSuccess(api("/panel/api/clients/add", body.toString()), "добавление клиента");
            return;
        }
        // Проверено на живой панели 02.11: клиент адресуется по email (числовой id даёт «record not found»),
        // тело update - поля клиента плоско, БЕЗ id (id в теле - строка, числовой ломает разбор) + inboundIds
        ObjectNode merged = existing.get().deepCopy();
        merged.remove("id");
        merged.remove("traffic");
        merged.remove("createdAt");
        merged.remove("updatedAt");
        // панель 3.11 отдаёт allowedIPs строкой, а при обновлении ждёт массив (на панели 3.1 поля нет и ничего не меняется);
        // без этого продление, бонусные дни и разблокировка ключа на новой панели падали бы с «cannot unmarshal string»
        JsonNode allowedIps = merged.get("allowedIPs");
        if (allowedIps != null && allowedIps.isTextual()) {
            String raw = allowedIps.asText();
            ArrayNode list = merged.putArray("allowedIPs");
            for (String part : raw.split(",")) {
                if (!part.isBlank()) {
                    list.add(part.trim());
                }
            }
        }
        // 05.11: список клиентов отдаёт allowedIPs строкой, а update ждёт массив строк («cannot unmarshal string into ... allowedIPs of type []string») - приводим к массиву
        JsonNode allowed = merged.get("allowedIPs");
        if (allowed != null && !allowed.isArray()) {
            ArrayNode allowedArr = merged.putArray("allowedIPs");
            if (allowed.isTextual()) {
                for (String ip : allowed.asText().split(",")) {
                    if (!ip.isBlank()) allowedArr.add(ip.trim());
                }
            }
        }
        merged.put("totalGB", spec.totalBytes());
        merged.put("expiryTime", spec.expiryMs());
        merged.put("limitIp", spec.limitIp());
        merged.put("enable", spec.enable());
        merged.putArray("inboundIds").add(inboundId);
        requireSuccess(api("/panel/api/clients/update/" + pathKey(spec.email()), merged.toString()), "обновление клиента");
    }

    /** Удаляет клиента по метке; если его уже нет - ничего не делает. */
    public synchronized void deleteClient(String email) throws PanelException {
        if (findClient(email).isEmpty()) {
            return;
        }
        requireSuccess(api("/panel/api/clients/del/" + pathKey(email), "{}"), "удаление клиента");
    }

    private static String pathKey(String email) {
        return URLEncoder.encode(email, StandardCharsets.UTF_8);
    }

    private ObjectNode clientNode(ClientSpec s) {
        ObjectNode c = objectMapper.createObjectNode();
        c.put("email", s.email());
        c.put("subId", s.subId());
        c.put("uuid", s.uuid());
        c.put("password", s.password());
        c.put("auth", s.auth());
        c.put("flow", "");
        c.put("security", "");
        c.put("limitIp", s.limitIp());
        c.put("totalGB", s.totalBytes());
        c.put("expiryTime", s.expiryMs());
        c.put("enable", s.enable());
        c.put("tgId", 0);
        c.put("comment", s.comment());
        c.put("reset", 0);
        return c;
    }

    private void requireSuccess(JsonNode res, String what) throws PanelException {
        if (!res.path("success").asBoolean(false)) {
            throw new PanelException(what + ": " + res.path("msg").asText("нет ответа"));
        }
    }

    /** GET при body == null, иначе POST JSON. Ответ не-200 или не-JSON считаем протухшей сессией: логин заново и один повтор. */
    private JsonNode api(String path, String jsonBody) throws PanelException {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (!loggedIn) {
                login();
            }
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .header("X-CSRF-Token", csrf)
                    .header("X-Requested-With", "XMLHttpRequest");
            if (jsonBody == null) {
                b.GET();
            } else {
                b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonBody));
            }
            HttpResponse<String> r = exec(b);
            JsonNode node = parse(r.body());
            if (r.statusCode() == 200 && node != null) {
                return node;
            }
            loggedIn = false;
        }
        throw new PanelException("панель не ответила на " + path);
    }

    private void login() throws PanelException {
        HttpResponse<String> page = exec(HttpRequest.newBuilder(URI.create(baseUrl + "/")).GET());
        csrf = extractCsrf(page.body());
        String form = "username=" + URLEncoder.encode(user, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(pass, StandardCharsets.UTF_8);
        HttpResponse<String> r = exec(HttpRequest.newBuilder(URI.create(baseUrl + "/login"))
                .header("X-CSRF-Token", csrf)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)));
        JsonNode node = parse(r.body());
        if (r.statusCode() != 200 || node == null || !node.path("success").asBoolean(false)) {
            throw new PanelException("вход в панель не удался: HTTP " + r.statusCode());
        }
        // после входа токен меняется: берём новый с внутренней страницы
        HttpResponse<String> panel = exec(HttpRequest.newBuilder(URI.create(baseUrl + "/panel/")).GET());
        String fresh = extractCsrf(panel.body());
        if (!fresh.isEmpty()) {
            csrf = fresh;
        }
        loggedIn = true;
    }

    private static String extractCsrf(String html) {
        Matcher m = CSRF.matcher(html == null ? "" : html);
        return m.find() ? m.group(1) : "";
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }

    private HttpResponse<String> exec(HttpRequest.Builder b) throws PanelException {
        try {
            return httpClient.send(b.timeout(REQUEST_TIMEOUT).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            loggedIn = false;
            throw new PanelException("панель недоступна: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PanelException("запрос к панели прерван", e);
        }
    }
}
