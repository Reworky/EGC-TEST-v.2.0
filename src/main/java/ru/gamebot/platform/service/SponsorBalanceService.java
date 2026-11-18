package ru.gamebot.platform.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.repository.AppSettingRepository;

/**
 * Спонсорский баланс (решение владельца 18.11.2026): токены с банковских миссий и внешних партнёрских квестов копятся отдельно от игрового баланса.
 * Они уже оплачены спонсором, поэтому выводятся по курсу 100% (100 EXC = 1 ₽) без месячных лимитов, порога 10 000 EXC и партнёрского статуса;
 * проверки на мультиаккаунты (телефон, возраст аккаунта, число квестов, коды дня, заморозка, одна заявка в сутки) остаются.
 * Подписки на каналы спонсоров и награды за рекламу идут на игровой баланс, как раньше. Накопленное до запуска не переносится (начинаем с нуля).
 *
 * Всё за выключателем app_settings «sponsor.balance.enabled» (по умолчанию выключен): пока он выключен, начисления идут на игровой баланс, как раньше.
 * Включать только вместе с готовым выводом со спонсорского баланса, иначе токены нельзя будет потратить.
 */
@Service
@RequiredArgsConstructor
public class SponsorBalanceService {

    public static final String ENABLED_KEY = "sponsor.balance.enabled";

    private final AppSettingRepository appSettingRepository;

    public boolean enabled() {
        return appSettingRepository.findById(ENABLED_KEY)
                .map(ru.gamebot.platform.domain.model.AppSetting::getValue)
                .map(v -> "true".equalsIgnoreCase(v.trim()))
                .orElse(false);
    }

    /** Награда этого квеста идёт на спонсорский баланс: банковская миссия партнёра или внешний партнёрский квест (подтверждает сеть/партнёр). */
    public static boolean isSponsorBalanceQuest(Quest quest) {
        return quest != null && (quest.isExternalAutoApprove() || PartnerMissions.forQuest(quest) != null);
    }

    /** Применяется ли спонсорский баланс к этому квесту прямо сейчас (выключатель включён и тип квеста подходит). */
    public boolean applies(Quest quest) {
        return isSponsorBalanceQuest(quest) && enabled();
    }
}
