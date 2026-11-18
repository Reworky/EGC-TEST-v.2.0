package ru.gamebot.platform.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.repository.AppSettingRepository;
import ru.gamebot.platform.domain.repository.AppUserRepository;

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
    private final AppUserRepository appUserRepository;
    private final ExcTransactionService excTx;

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

    /** Результат ручной операции админа: игрок и балансы после неё (игровой EXC и спонсорский EXCASH). */
    public record AdminOpResult(AppUser user, long gameCoins, long sponsorCoins) {}

    /** Ручное начисление (amount > 0) или списание (amount < 0) EXCASH на спонсорском балансе игрока (админ-команда /excash). Ниже нуля не списывает. */
    @Transactional
    public AdminOpResult adminAdjust(Long telegramId, long amount, String description) {
        if (amount == 0) throw new IllegalArgumentException("Сумма не может быть нулевой.");
        AppUser user = appUserRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new IllegalArgumentException("Игрок с таким Telegram ID не найден."));
        excTx.refreshCoins(user);
        if (user.getSponsorCoins() + amount < 0) {
            throw new IllegalArgumentException("На спонсорском балансе только " + user.getSponsorCoins() + " EXCASH, списать " + (-amount) + " нельзя.");
        }
        excTx.creditSponsor(user, amount, amount > 0 ? ExcTransactionService.BONUS : ExcTransactionService.DEBIT,
                (amount > 0 ? "Админ: начисление EXCASH" : "Админ: списание EXCASH") + (description == null || description.isBlank() ? "" : " (" + description + ")"));
        return new AdminOpResult(user, user.getCoins(), user.getSponsorCoins());
    }

    /** Перенос amount с игрового баланса (EXC) на спонсорский (EXCASH) 1 к 1 (админ-команда /tocash): для игроков, которые просят перенести старые спонсорские заработки. Одна транзакция. */
    @Transactional
    public AdminOpResult adminMoveToSponsor(Long telegramId, long amount) {
        if (amount <= 0) throw new IllegalArgumentException("Сумма переноса должна быть больше нуля.");
        AppUser user = appUserRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new IllegalArgumentException("Игрок с таким Telegram ID не найден."));
        excTx.refreshCoins(user);
        if (user.getCoins() < amount) {
            throw new IllegalArgumentException("На игровом балансе только " + user.getCoins() + " EXC, перенести " + amount + " нельзя.");
        }
        excTx.creditExc(user, -amount, ExcTransactionService.DEBIT, "Админ: перенос на спонсорский баланс (EXCASH)");
        excTx.creditSponsor(user, amount, ExcTransactionService.BONUS, "Админ: перенос с игрового баланса (EXC)");
        return new AdminOpResult(user, user.getCoins(), user.getSponsorCoins());
    }

    /** Применяется ли спонсорский баланс к этому квесту прямо сейчас (выключатель включён и тип квеста подходит). */
    public boolean applies(Quest quest) {
        return isSponsorBalanceQuest(quest) && enabled();
    }
}
