package ru.gamebot.platform.config;

import jakarta.validation.constraints.NotBlank;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    @NotBlank(message = "BOT_TOKEN must be provided")
    private String botToken;

    @NotBlank(message = "BOT_USERNAME must be provided")
    private String botUsername;

    private String clubName = "Game Quest Club";
    private String supportUsername = "support_manager";
    private Set<Long> adminIds = new HashSet<>();
    private Set<Long> moderatorIds = new HashSet<>();
    private String adminIdsRaw = "";
    private String moderatorIdsRaw = "";
    private String initialAdminId = "";
}
