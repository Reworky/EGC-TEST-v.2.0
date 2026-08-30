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
    private String requiredChannelId = "";
    private String requiredChannelUsername = "@exgamingclub";
    private String requiredChannelUrl = "";
    private String requiredChannelTitle = "EXPERIENCE GAMING CLUB";
    private String payoutChannelUsername = "@egc_payouts";

    // REST API
    private String jwtSecret = "egc-default-secret-change-in-production-min-32-chars";
    private long jwtExpirationDays = 30;
    private String corsAllowedOrigins = "http://localhost:3000";

    // Шаблоны быстрого отклонения отчётов по квестам ({название_задания}, {никнейм_в_боте}, {краткое_условие_задания})
    private String rejectTemplateNotRelevant =
            "Отчёт не принят: содержимое не относится к заданию «{название_задания}». "
                    + "Пришлите скриншот/подтверждение именно выполненного задания.";
    private String rejectTemplateNicknameMismatch =
            "Отчёт не принят: никнейм в игре не совпадает с никнеймом, указанным в EGC ({никнейм_в_боте}). "
                    + "Проверьте, что вы отправляете отчёт с того же аккаунта, что привязан к боту, и приложите скриншот профиля с ником.";
    private String rejectTemplateInsufficientData =
            "Отчёт не принят: недостаточно данных для подтверждения выполнения задания «{название_задания}». "
                    + "Условия: {краткое_условие_задания}. Пришлите полный отчёт, подтверждающий выполнение.";
}
