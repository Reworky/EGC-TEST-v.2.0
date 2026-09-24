package ru.gamebot.platform.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.gamebot.platform.service.ErrorMonitorService;

/** Считает ответы 4xx/5xx на /api/** (мини-апп) для админской кнопки «🩺 Проверка ошибок». Стоит первым в цепочке,
 * чтобы видеть и отказы Spring Security (403). Тело запроса/ответа и данные игрока не читает и не хранит -
 * только метод, путь с обезличенными числами (/api/quests/N/take) и код ответа. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class HttpStatsFilter extends OncePerRequestFilter {

    private final ErrorMonitorService monitor;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException e) {
            monitor.recordHttp(500, route(request));
            throw e;
        }
        int status = response.getStatus();
        if (status >= 400) {
            monitor.recordHttp(status, route(request));
        }
    }

    private static String route(HttpServletRequest request) {
        return request.getMethod() + " " + request.getRequestURI().replaceAll("/[0-9]+", "/N");
    }
}
