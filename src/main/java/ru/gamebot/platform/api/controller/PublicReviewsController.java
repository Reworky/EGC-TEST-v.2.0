package ru.gamebot.platform.api.controller;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.gamebot.platform.domain.enums.BotReviewStatus;
import ru.gamebot.platform.domain.model.BotReview;
import ru.gamebot.platform.domain.repository.BotReviewRepository;

/** Отзывы игроков для главной страницы сайта: только опубликованные в канале @egc_payouts (прошли модерацию), 4-5 звёзд, с текстом.
 *  Ники замаскированы так же, как в ленте выплат; кэш на 10 минут (эндпоинт публичный). */
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/public/reviews")
@RequiredArgsConstructor
public class PublicReviewsController {

    private static final int LIMIT = 6;
    private static final int MAX_TEXT = 260;

    private final BotReviewRepository botReviewRepository;

    private List<Map<String, Object>> cached;
    private LocalDateTime cachedAt;

    @GetMapping
    public synchronized Map<String, Object> reviews() {
        LocalDateTime now = LocalDateTime.now();
        if (cached == null || cachedAt == null || Duration.between(cachedAt, now).toMinutes() >= 10) {
            List<Map<String, Object>> out = new ArrayList<>();
            try {
                for (BotReview r : botReviewRepository.findByStatusAndStarsGreaterThanEqualOrderByCreatedAtDesc(
                        BotReviewStatus.PUBLISHED, 4, PageRequest.of(0, 40))) {
                    String text = r.getText() == null ? "" : r.getText().trim();
                    if (text.length() < 10) continue;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("stars", Math.min(5, r.getStars()));
                    m.put("text", text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT).trim() + "…" : text);
                    m.put("name", maskNickname(r.getUser() == null ? null : r.getUser().getNickname()));
                    out.add(m);
                    if (out.size() >= LIMIT) break;
                }
                cached = out;
                cachedAt = now;
            } catch (Exception e) {
                if (cached == null) cached = List.of();
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", cached);
        return body;
    }

    private static String maskNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) return "Игрок";
        int[] cps = nickname.trim().codePoints().toArray();
        if (cps.length <= 3) return new String(cps, 0, 1) + "…";
        return new String(cps, 0, 2) + "…" + new String(cps, cps.length - 1, 1);
    }
}
