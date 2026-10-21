package ru.gamebot.platform.api.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.service.AdminService;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final AppUserRepository appUserRepository;
    private final AdminService adminService;

    /** telegramId -> {момент проверки (мс), 1 если заблокирован}: чтобы не ходить в базу на каждый запрос мини-аппа. */
    private final Map<Long, long[]> blockCache = new ConcurrentHashMap<>();
    private static final long BLOCK_CACHE_TTL_MS = 15_000;

    /** Заблокированный админом игрок не должен ничего делать через мини-апп (вывод, магазин, квесты), как и в боте. Админов не режем. */
    private boolean isBlockedNonAdmin(Long telegramId) {
        long now = System.currentTimeMillis();
        long[] c = blockCache.get(telegramId);
        if (c != null && now - c[0] < BLOCK_CACHE_TTL_MS) return c[1] == 1;
        boolean blocked = appUserRepository.findByTelegramId(telegramId)
                .map(u -> u.isBlocked() && !adminService.isAdmin(telegramId)).orElse(false);
        if (blockCache.size() > 20_000) blockCache.clear();
        blockCache.put(telegramId, new long[]{now, blocked ? 1 : 0});
        return blocked;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            if (jwtService.isValid(token)) {
                Long telegramId = jwtService.extractTelegramId(token);
                if (isBlockedNonAdmin(telegramId)) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"error\":\"Аккаунт заблокирован администратором. Напишите в поддержку.\"}");
                    return;
                }
                var auth = new UsernamePasswordAuthenticationToken(
                        telegramId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(request, response);
    }
}
