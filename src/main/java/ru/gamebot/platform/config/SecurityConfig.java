package ru.gamebot.platform.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import ru.gamebot.platform.api.auth.JwtAuthFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Действия с квестом — нужен JWT (проверяем раньше общего правила ниже)
                        .requestMatchers(HttpMethod.POST, "/api/quests/*/take", "/api/quests/*/report").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/quests/mine").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/quests/mine/*/cancel").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/shop/items/*/purchase").authenticated()
                        // Публичные эндпоинты — без токена
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/leaderboard").permitAll()
                        .requestMatchers("/api/quests/**").permitAll()
                        .requestMatchers("/api/shop/**").permitAll()
                        .requestMatchers("/api/stats").permitAll()
                        // Личный кабинет — нужен JWT
                        .requestMatchers("/api/profile/**").authenticated()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
