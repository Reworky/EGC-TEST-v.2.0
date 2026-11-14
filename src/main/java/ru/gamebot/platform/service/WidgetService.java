package ru.gamebot.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.config.AppProperties;
import ru.gamebot.platform.domain.model.AppSetting;
import ru.gamebot.platform.domain.repository.AppSettingRepository;

/**
 * HTML-виджеты в сообщениях бота (Bot API 10.4 от 09.11.2026, метод sendWidget): интерактивный блок прямо в пузыре сообщения, только в личных чатах, без пересылки.
 * Библиотека бота (telegrambots 6.9) метода не знает, поэтому вызываем Bot API напрямую по HTTP. Сервис ничего не ломает: при любой ошибке (старый клиент, отказ API, сеть)
 * возвращает false, и вызывающий код показывает обычный текст.
 *
 * Выключено по умолчанию: app_settings widget.enabled = true включает кнопки для игроков (команда админа /widget on); /widget test шлёт виджет админу при выключенном флаге.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WidgetService {

    public static final String KEY_ENABLED = "widget.enabled";
    private static final int MAX_HTML_BYTES = 32_768;

    private final AppProperties appProperties;
    private final AppSettingRepository appSettingRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newHttpClient();

    public boolean isEnabled() {
        return appSettingRepository.findById(KEY_ENABLED).map(AppSetting::getValue).map(v -> "true".equalsIgnoreCase(v.trim())).orElse(false);
    }

    public void setEnabled(boolean on) {
        AppSetting s = new AppSetting();
        s.setKey(KEY_ENABLED);
        s.setValue(String.valueOf(on));
        appSettingRepository.save(s);
    }

    /** Результат отправки: ok - Bot API принял; error - ответ Bot API (или исключение) для админской диагностики. */
    public record SendResult(boolean ok, String error) {}

    /** Отправляет виджет в личный чат. true - Bot API принял; false - не получилось (в лог пишется причина), вызывающий показывает запасной вариант. */
    public boolean send(Long chatId, String html, int height, String caption) {
        return sendDetailed(chatId, html, height, caption).ok();
    }

    public SendResult sendDetailed(Long chatId, String html, int height, String caption) {
        return sendDetailed(chatId, html, height, caption, false);
    }

    /** allowQueries - разрешить виджету запросы к боту (в документации: без этого флага все запросы виджета отклоняются). */
    public SendResult sendDetailed(Long chatId, String html, int height, String caption, boolean allowQueries) {
        try {
            if (html == null || html.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_HTML_BYTES) {
                log.warn("Widget html is empty or too large");
                return new SendResult(false, "html пустой или больше 32 КБ");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("chat_id", chatId);
            body.put("html", html);
            body.put("height", Math.max(50, Math.min(600, height)));
            if (allowQueries) body.put("allow_queries", true);
            if (caption != null && !caption.isBlank()) {
                body.put("caption", caption);
                body.put("parse_mode", "HTML");
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.telegram.org/bot" + appProperties.getBotToken() + "/sendWidget"))
                    .header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            boolean ok = response.statusCode() == 200 && objectMapper.readTree(response.body()).path("ok").asBoolean(false);
            if (!ok) {
                log.warn("sendWidget rejected for chat {}: HTTP {} {}", chatId, response.statusCode(), response.body());
            }
            return new SendResult(ok, ok ? null : "HTTP " + response.statusCode() + ": " + response.body());
        } catch (Exception e) {
            log.warn("sendWidget failed for chat {}", chatId, e);
            return new SendResult(false, "исключение: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    /** Виджет «Сколько ты получишь»: ползунок EXC и две суммы в рублях (курс игрока и партнёрский 100%). Считает прямо в виджете, доступа в сеть не нужно. */
    public static String rateCalculatorHtml(long excStart, int ownPercent) {
        long exc = Math.max(1_000, Math.min(300_000, excStart));
        return RATE_CALCULATOR
                .replace("__EXC__", String.valueOf(exc))
                .replace("__OWN__", String.valueOf(Math.max(1, Math.min(100, ownPercent))));
    }

    private static final String RATE_CALCULATOR = """
            <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
            *{box-sizing:border-box}
            body{margin:0;padding:14px;background:#0f1530;color:#fff;font-family:-apple-system,Roboto,Arial,sans-serif}
            h1{font-size:16px;margin:0 0 4px;color:#ffd75a}
            .sub{font-size:12px;opacity:.65;margin-bottom:10px}
            .exc{display:flex;justify-content:space-between;align-items:baseline;margin-bottom:6px}
            .exc b{font-size:22px}
            input[type=range]{width:100%;margin:2px 0 12px;accent-color:#a56bff}
            .card{border-radius:12px;padding:10px 12px;margin-bottom:8px}
            .own{background:#1d2650}.par{background:#2b1a5e;border:1px solid #a56bff}
            .lbl{font-size:12px;opacity:.8}
            .val{font-size:24px;font-weight:700;margin:2px 0 6px}
            .bar{height:7px;border-radius:4px;background:rgba(255,255,255,.12);overflow:hidden}
            .bar i{display:block;height:100%;border-radius:4px}
            .own .bar i{background:#6ee4fe}.par .bar i{background:linear-gradient(90deg,#6ee4fe,#a56bff,#f67de0)}
            .gain{text-align:center;font-size:14px;margin-top:8px;color:#ffd75a;font-weight:600}
            </style></head><body>
            <h1>Сколько ты получишь</h1>
            <div class="sub">Двигай ползунок: сколько рублей за твои EXC при обычном и партнёрском курсе</div>
            <div class="exc"><span>EXC</span><b id="exc"></b></div>
            <input id="r" type="range" min="1000" max="300000" step="500" value="__EXC__">
            <div class="card own"><div class="lbl">Сейчас · курс __OWN__%</div><div class="val" id="own"></div><div class="bar"><i id="ownBar"></i></div></div>
            <div class="card par"><div class="lbl">Партнёр · курс 100%</div><div class="val" id="par"></div><div class="bar"><i style="width:100%"></i></div></div>
            <div class="gain" id="gain"></div>
            <script>
            var OWN=__OWN__/100;
            function f(n){return Math.round(n).toString().replace(/\\B(?=(\\d{3})+(?!\\d))/g,' ');}
            function upd(){
              var e=+document.getElementById('r').value, p=e/100, o=p*OWN;
              document.getElementById('exc').textContent=f(e);
              document.getElementById('own').textContent='≈ '+f(o)+' ₽';
              document.getElementById('par').textContent='≈ '+f(p)+' ₽';
              document.getElementById('ownBar').style.width=Math.round(OWN*100)+'%';
              document.getElementById('gain').textContent='Партнёр получает больше на '+f(p-o)+' ₽';
            }
            document.getElementById('r').addEventListener('input',upd);upd();
            </script></body></html>
            """;
}
