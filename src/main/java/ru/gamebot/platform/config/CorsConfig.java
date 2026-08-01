package ru.gamebot.platform.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
@RequiredArgsConstructor
public class CorsConfig {

    private final AppProperties props;

    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

        // Public stats endpoint — open to any origin (no credentials needed)
        CorsConfiguration publicStats = new CorsConfiguration();
        publicStats.addAllowedOrigin("*");
        publicStats.addAllowedHeader("*");
        publicStats.addAllowedMethod("GET");
        source.registerCorsConfiguration("/api/stats", publicStats);

        // Authenticated API endpoints
        CorsConfiguration config = new CorsConfiguration();
        for (String origin : props.getCorsAllowedOrigins().split(",")) {
            config.addAllowedOrigin(origin.trim());
        }
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        config.setAllowCredentials(true);
        source.registerCorsConfiguration("/api/**", config);

        return new CorsFilter(source);
    }
}
