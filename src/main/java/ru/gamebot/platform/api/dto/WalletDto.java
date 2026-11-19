package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WalletDto {
    private long coins;
    /** Спонсорский баланс (токены с банковских миссий и партнёрских квестов, вывод по курсу 100% без лимитов); показывается, когда sponsorBalanceEnabled. */
    private long sponsorCoins;
    /** Сколько EXCASH ждёт подтверждения банка (банковские квесты с клубной наградой в EXCASH; в баланс не входит). */
    private long sponsorPendingExcash;
    private boolean sponsorBalanceEnabled;
    /** Версия API спонсорского баланса (1 - вывод/покупки по флагу sponsor): мини-апп показывает спонсорские элементы только если сервер её отдаёт (защита от деплоя интерфейса раньше бота). */
    private int sponsorApi;
    private int excBonusPercent;
    private long tickets;
    private long xp;
    private long weeklyXp;
    private int healthRatioPercent;
    /** Курс вывода игрока при включённых уровнях (100% у партнёра и до 10 000 EXC за всё время, дальше сниженный); null - уровни выключены, курс = healthRatioPercent. */
    private Integer withdrawalRatioPercent;
    private long monthlyWithdrawalLimit;
    private long remainingWithdrawalLimit;
    private boolean dailyBonusAvailable;
    private int streakDays;
    private long nextDailyBonusExc;
    private boolean chestAvailable;
    /** Активна подписка EGC Pass — бесплатный сундук дня у неё сразу улучшенный (UserService.openChest). */
    private boolean egcPass;
    private long fixedRubBalance;
    private boolean phoneConfirmed;
    /** >0 — серия входов прервалась и есть предложение восстановить её за Stars. */
    private int restorableStreakDays;
    private int streakRestorePriceStars;
    /** Прерванную серию можно вернуть бесплатно просмотром рекламы (потеряно немного дней, не чаще раза в 14 дней). */
    private boolean streakAdRestoreAvailable;
    /** Сколько квестов и дней осталось до открытия вывода (0/0 - условия выполнены); мини-апп показывает это новичкам над балансом. */
    private int withdrawalQuestsLeft;
    private int withdrawalDaysLeft;
    /** Часов до открытия вывода (округление вверх); мини-апп показывает часы вместо дней, когда осталось меньше суток. */
    private int withdrawalHoursLeft;
    /** Условие вывода «коды дня»: сколько кодов нужно за 30 дней (0 - условие выключено) и сколько игрок уже ввёл. */
    private int withdrawalCodesNeed;
    private int withdrawalCodesHave;
    /** Показывать сравнение «курс сейчас / курс партнёра» (не партнёр, 3+ квеста, уровни курса включены; 10.11.2026). */
    private boolean partnerCompare;
    /** «Получить до N ₽» за спонсорские квесты (не партнёрам; уже выполненные игроком не входят; 0 - блок не показывать). Как на экранах вывода в боте. */
    private long sponsoredPrizeRub;
}
