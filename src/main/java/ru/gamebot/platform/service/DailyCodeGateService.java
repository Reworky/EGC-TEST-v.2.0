package ru.gamebot.platform.service;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.model.AppSetting;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.repository.AppSettingRepository;

/** Условие вывода «коды дня» (16.11.2026, владелец): чтобы вывести деньги, игрок должен ввести минимум N кодов дня за последние 30 дней (скользящее окно, не календарный месяц).
 *  Так в канале появляется регулярная активность. N хранится в настройке {@value #KEY} (по умолчанию {@value #DEFAULT_REQUIRED}; в начале декабря владелец поднимает до 5, команда /codegate; 0 - условие выключено).
 *  Код дня выходит раз в ClubActivityService.CODE_EVERY_DAYS дня, поэтому «N кодов» - это N разных дней. Проверка стоит в RewardService.withdrawalEligibilityBlock - единственной точке для бота, мини-аппа и сайта. */
@Service
@RequiredArgsConstructor
public class DailyCodeGateService {

    public static final String KEY = "withdrawal.code_gate.required";
    public static final int DEFAULT_REQUIRED = 3;
    public static final int WINDOW_DAYS = 30;

    private final EntityManager entityManager;
    private final AppSettingRepository settingRepository;

    /** Сколько кодов нужно за окно; 0 - условие выключено. */
    public int required() {
        try {
            return settingRepository.findById(KEY)
                    .map(s -> Math.max(0, Math.min(30, Integer.parseInt(s.getValue().trim()))))
                    .orElse(DEFAULT_REQUIRED);
        } catch (Exception e) {
            return DEFAULT_REQUIRED;
        }
    }

    public int setRequired(int n) {
        int v = Math.max(0, Math.min(30, n));
        AppSetting s = settingRepository.findById(KEY).orElseGet(() -> {
            AppSetting created = new AppSetting();
            created.setKey(KEY);
            return created;
        });
        s.setValue(String.valueOf(v));
        settingRepository.save(s);
        return v;
    }

    /** Сколько кодов дня игрок ввёл за последние WINDOW_DAYS дней (включая сегодня). */
    public long codesInWindow(Long userId) {
        LocalDate from = LocalDate.now().minusDays(WINDOW_DAYS - 1L);
        Object r = entityManager.createNativeQuery("SELECT COUNT(*) FROM daily_code_claims WHERE user_id = ?1 AND claim_date >= ?2")
                .setParameter(1, userId).setParameter(2, from).getSingleResult();
        return r == null ? 0 : ((Number) r).longValue();
    }

    /** null - условие выполнено или выключено; иначе объяснение игроку. */
    public String block(AppUser user) {
        int need = required();
        if (need <= 0 || user == null || user.getId() == null) return null;
        long have = codesInWindow(user.getId());
        if (have >= need) return null;
        return "Для вывода нужно ввести " + need + " " + codesWord(need) + " дня за последние " + WINDOW_DAYS + " дней. У вас: " + have + ". "
                + "Код дня выходит в канале, вводится в боте кнопкой «🔑 Код дня».";
    }

    private static String codesWord(int n) {
        int m10 = n % 10, m100 = n % 100;
        if (m10 == 1 && m100 != 11) return "код";
        if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return "кода";
        return "кодов";
    }
}
