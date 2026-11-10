package ru.gamebot.platform.service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Банки-партнёры как места назначения рублёвого вывода (05.11.2026): после порога 10 000 EXC выводить можно только в банк, где игрок наш реферал (одобрен партнёрский квест этого банка).
 *  Банк определяется по тому, что игрок вписал на шаге «банк»: ищется любое из слов aliases (без регистра, «ё» = «е»). «Т-Мобайл SIM» намеренно не входит: это не реферал Т-Банка.
 *  Новый банк: константа здесь (и в ALL), миссия в PartnerMissions, квест в QuestSeeder. */
public final class WithdrawalPartners {

    /** Банк-место назначения: миссия партнёра (квест, который делает игрока рефералом) и слова для распознавания названия банка. */
    public record Bank(PartnerMission mission, String title, String genitive, List<String> aliases) {}

    public static final Bank OTP = new Bank(PartnerMissions.OTP, "Банк-партнёр", "банка-партнёра", List.of("отп", "otp"));
    public static final Bank T_BANK = new Bank(PartnerMissions.T_BLACK, "Т-Банк", "Т-Банка",
            List.of("т-банк", "т банк", "тбанк", "т-банка", "тинькофф", "тиньков", "tinkoff", "t-bank", "tbank", "t bank"));

    /** Места назначения вывода сверх порога. Сейчас ТОЛЬКО ОТП Банк (решение владельца 05.11.2026: единственный основной банк проекта); Т-Банк добавить сюда, когда появится выбор банков. */
    public static final List<Bank> ALL = List.of(OTP);

    private WithdrawalPartners() {}

    /** Банк-партнёр по тексту из поля «банк»; пусто - такого партнёра нет (любой другой банк). */
    public static Optional<Bank> matchBank(String bankText) {
        if (bankText == null) return Optional.empty();
        String norm = bankText.toLowerCase(Locale.ROOT).replace('ё', 'е').trim();
        return ALL.stream().filter(b -> b.aliases().stream().anyMatch(norm::contains)).findFirst();
    }
}
