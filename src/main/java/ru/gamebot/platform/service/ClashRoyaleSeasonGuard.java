package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Optional;

/** Предупреждение о границе сезона Clash Royale при создании трофи-турнира (ТЗ «Турнир Clash Royale», п. 4.2).
 *
 *  Считаем, что трофеи могут частично сбрасываться в начале календарного месяца. Если сброс попадёт между стартовым и
 *  финальным снимками, прирост участника искажается самим сбросом; если между регистрацией и стартом - падение трофеев
 *  ложно помечается как подозрительное (TrophyTournamentService.checkAnomaly) и призы удерживаются. Точный день сброса
 *  заранее не известен, поэтому предупреждаем с запасом: окно «регистрация -> финиш» пересекает границу месяца либо старт
 *  или финиш приходятся на первые дни месяца. Только предупреждение - админ подтверждает создание осознанно (решение по
 *  открытому вопросу ТЗ №1). Все даты - UTC, как во всём турнирном модуле. */
public final class ClashRoyaleSeasonGuard {

    /** Дни от начала месяца, в которые снимок считается «на границе сезона». */
    static final int BOUNDARY_DAYS = 3;

    private ClashRoyaleSeasonGuard() {
    }

    /** registrationOpen - момент, с которого идёт регистрация (создание турнира). */
    public static Optional<String> check(LocalDateTime registrationOpen, LocalDateTime start, LocalDateTime end) {
        boolean crossesMonth = !YearMonth.from(registrationOpen).equals(YearMonth.from(end));
        boolean startNear = start.getDayOfMonth() <= BOUNDARY_DAYS;
        boolean endNear = end.getDayOfMonth() <= BOUNDARY_DAYS;
        if (!crossesMonth && !startNear && !endNear) return Optional.empty();

        StringBuilder sb = new StringBuilder("⚠️ <b>Возможная граница сезона Clash Royale</b>\n\n");
        if (crossesMonth) {
            sb.append("• Период от открытия регистрации до финиша пересекает смену месяца.\n");
        }
        if (startNear) {
            sb.append("• Стартовый снимок приходится на первые ").append(BOUNDARY_DAYS).append(" дня месяца.\n");
        }
        if (endNear) {
            sb.append("• Финальный снимок приходится на первые ").append(BOUNDARY_DAYS).append(" дня месяца.\n");
        }
        sb.append("\nВ начале месяца Clash Royale может частично сбрасывать трофеи. Тогда прирост участников исказит сам сброс, "
                + "а падение трофеев между регистрацией и стартом будет помечено как подозрительное и призы задержатся "
                + "до ручной проверки. Лучше выбрать даты внутри одного месяца, подальше от его начала.");
        return Optional.of(sb.toString());
    }
}
