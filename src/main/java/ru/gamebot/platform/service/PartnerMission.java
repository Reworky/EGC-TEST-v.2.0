package ru.gamebot.platform.service;

import java.time.LocalDate;
import lombok.Builder;

/** Партнёрская «миссия» с банком или оператором (ОТП Карта, Т-Банк Black, ...): числа и тексты, которые различаются между партнёрами. Экраны миссии, напоминания,
 *  отчёт и модерация берут всё отсюда (см. {@link PartnerMissions}); сам путь игрока (этапы 1-5, дата, «Я заплатил(а)», «Банк вернул...», бонус) общий.
 *  Тексты с плейсхолдерами: {date} - дата выплаты банка, {DONATE} - слово donatov.net со ссылкой (если в шаге 2 она есть). */
@Builder
public record PartnerMission(
        /** Короткий ключ, колбэки и настройки не используют (только код). */
        String key,
        /** quests.game_name: по нему заявки находятся в напоминаниях, бейдже и /…bonus. */
        String gameName,
        String questTitle,
        /** Подпись кнопки в списке спонсорских квестов. */
        String listLabel,
        /** Файл баннера в ресурсах бота или null. */
        String bannerResource,
        /** «Квест «ОТП Карта»» в сообщении менеджеру. */
        String shortTitle,
        /** «карта ОТП Банка» в заголовке «Миссия: карта ОТП Банка». */
        String missionTitle,
        /** «Карта ОТП» в напоминании «Карта ОТП ждёт тебя». */
        String waitingName,
        /** EXC за одобренный отчёт. */
        long rewardExc,
        /** EXC после выплаты банка (/…bonus). */
        long bonusExc,
        /** Дней на покупку (считаются от даты, которую игрок указал на этапе 2). */
        int windowDays,
        /** 0 - банк платит с 25-го числа месяца, следующего за покупкой; N - в течение N дней после покупки. */
        int bankPayDays,
        /** Админ-команда начисления бонуса без слэша: otpbonus, tbbonus. */
        String bonusCommand,
        String stage1Text,
        String stage2WaitText,
        String stage2WaitButton,
        String dateQuestion,
        String dateHint,
        String stage2Text,
        /** Подпись кнопки-совета в шаге 2 или null, если ссылки-совета нет. */
        String stage2LinkLabel,
        String stage3Text,
        /** Строка этапа 4 «что дальше, 1»: «С {date} банк вернёт тебе 500 ₽ на карту.» */
        String stage4Line1,
        /** Слово перед датой в списке этапов: «с» (25-го числа) или «до» (в течение N дней). */
        String bankDateWord,
        /** Что должно быть на скриншоте 1 при отправке отчёта (скриншот 2 - «Детали операции», общий). */
        String report1Prompt,
        /** {подпись кнопки, текст игроку} - «вернуть на доработку». */
        String[][] returnReasons,
        /** {подпись кнопки, текст игроку} - «отклонить окончательно». */
        String[][] finalReasons) {

    /** Дата выплаты банка: с 25-го числа следующего месяца или через bankPayDays дней после покупки. */
    public LocalDate bankDate(LocalDate purchaseDay) {
        return bankPayDays > 0 ? purchaseDay.plusDays(bankPayDays) : purchaseDay.plusMonths(1).withDayOfMonth(25);
    }
}
