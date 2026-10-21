package ru.gamebot.platform.api.controller;

import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.gamebot.platform.service.SeasonalEventService;

/** Текущее сезонное событие для баннера мини-аппа (без данных игрока): {active:false} или {active:true, code, title, themeKey, endsAt (UTC, ISO)}. */
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final SeasonalEventService seasonalEventService;

    @GetMapping("/current")
    public Map<String, Object> current() {
        Map<String, Object> out = new LinkedHashMap<>();
        var event = seasonalEventService.current();
        if (event.isEmpty()) {
            out.put("active", false);
            return out;
        }
        var e = event.get();
        out.put("active", true);
        out.put("code", e.code());
        out.put("title", e.title());
        out.put("themeKey", e.theme());
        // Конец события по МСК переводим в UTC, чтобы таймер в браузере считался одинаково в любой временной зоне.
        out.put("endsAt", e.end().atZone(SeasonalEventService.MSK).withZoneSameInstant(ZoneOffset.UTC).toInstant().toString());
        return out;
    }
}
