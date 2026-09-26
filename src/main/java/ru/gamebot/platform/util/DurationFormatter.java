package ru.gamebot.platform.util;

/** Человекочитаемая длительность кулдауна (часы для обычных случаев, дни — для длинных, например
 * 336ч у квестов категории «Сложные»). Общий класс для бота и API мини-аппа, чтобы оба показывали
 * игроку одинаковый, реальный текст — раньше бот и мини-апп независимо зашивали свои тексты и
 * расходились (мини-апп до сих пор говорил "24 часа" безусловно). */
public final class DurationFormatter {

    private DurationFormatter() {
    }

    public static String pluralHours(long n) {
        if (n % 100 >= 11 && n % 100 <= 14) return "часов";
        return switch ((int) (n % 10)) {
            case 1 -> "час";
            case 2, 3, 4 -> "часа";
            default -> "часов";
        };
    }

    public static String pluralDays(long n) {
        if (n % 100 >= 11 && n % 100 <= 14) return "дней";
        return switch ((int) (n % 10)) {
            case 1 -> "день";
            case 2, 3, 4 -> "дня";
            default -> "дней";
        };
    }

    /** Реальная длительность в человекочитаемом виде (часы для коротких окон, дни — для длинных). */
    public static String format(long minutes) {
        long hours = Math.max(1, (minutes + 59) / 60);
        if (hours < 24) {
            return hours + " " + pluralHours(hours);
        }
        long days = hours / 24;
        return days + " " + pluralDays(days);
    }

    /** Точное оставшееся время для отсчёта («3 ч 25 мин», «45 мин», «1 д 4 ч»): игрок видит не округлённые часы, а реальный остаток до снятия кулдауна. */
    public static String formatExact(long minutes) {
        long m = Math.max(1, minutes);
        long days = m / (24 * 60);
        long hours = (m % (24 * 60)) / 60;
        long mins = m % 60;
        if (days > 0) {
            return days + " д" + (hours > 0 ? " " + hours + " ч" : "");
        }
        if (hours > 0) {
            return hours + " ч" + (mins > 0 ? " " + mins + " мин" : "");
        }
        return mins + " мин";
    }
}
