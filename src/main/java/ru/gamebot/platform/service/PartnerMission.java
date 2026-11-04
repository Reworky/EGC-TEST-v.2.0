package ru.gamebot.platform.service;

import java.time.LocalDate;
import lombok.Builder;

/** Партнёрская «миссия» с банком или оператором (ОТП Карта, Т-Банк Black, Т-Мобайл SIM, ...): числа и тексты, которые различаются между партнёрами. Экраны миссии, напоминания,
 *  отчёт и модерация берут всё отсюда (см. {@link PartnerMissions}); сам путь игрока (этапы 1-5, дата, «Я заплатил(а)», кнопка «банк заплатил», бонус) общий.
 *  Плейсхолдеры в шаблонах: {reward} - EXC за отчёт, {bonus} - EXC после выплаты банка, {date} - дата выплаты банка, {date7} - она же плюс неделя, {DONATE} - слово donatov.net со ссылкой,
 *  {days} - дней осталось, {win} - срок окна, дата. */
@Builder
public record PartnerMission(
        /** Короткий ключ (только код). */
        String key,
        /** quests.game_name: по нему заявки находятся в напоминаниях, бейдже и /…bonus. */
        String gameName,
        String questTitle,
        /** Подпись кнопки в списке спонсорских квестов. */
        String listLabel,
        /** Файл баннера карточки квеста в ресурсах бота или null. */
        String bannerResource,
        /** «Квест «ОТП Карта»» в сообщении менеджеру и в уведомлениях админам. */
        String shortTitle,
        /** «карта ОТП Банка» в заголовке «Миссия: карта ОТП Банка». */
        String missionTitle,
        /** «Карта ОТП» в напоминании «Карта ОТП ждёт тебя». */
        String waitingName,
        /** EXC за одобренный отчёт. */
        long rewardExc,
        /** EXC после выплаты банка (/…bonus). */
        long bonusExc,
        /** Дней на целевое действие (от даты, которую игрок указал на этапе 2). */
        int windowDays,
        /** 0 - банк платит с 25-го числа месяца, следующего за покупкой; N - не позже чем через N дней после покупки/пополнения. */
        int bankPayDays,
        /** Админ-команда начисления бонуса без слэша: otpbonus, tbbonus, tmbonus. */
        String bonusCommand,
        /** Четыре названия этапов: {карта/сим, платёж, докажи, банк}. */
        String[] stageNames,
        String stage1Text,
        String stage2WaitText,
        String stage2WaitButton,
        String dateQuestion,
        String dateHint,
        String stage2Text,
        /** Подпись кнопки-совета в шаге 2 или null, если ссылки-совета нет. */
        String stage2LinkLabel,
        /** Подпись кнопки «Я заплатил(а)» / «Я пополнил(а) баланс» на этапе 2. */
        String paidButton,
        String stage3Text,
        /** Экран этапа 4 после одобрения отчёта. */
        String stage4Text,
        /** Экран финала после начисления бонуса. */
        String finalText,
        /** Слово перед датой в списке этапов: «с» (25-го числа) или «до» (в течение N дней). */
        String bankDateWord,
        /** Подпись кнопки «банк заплатил» на этапе 4 и в напоминании. */
        String bankClaimButton,
        /** «Проверь карту.» / «Проверь баланс.» в напоминании о дне выплаты. */
        String bankDayCheck,
        /** Напоминание «до конца окна осталось {days} дн. (до {win})». */
        String deadlineReminder,
        /** Что должно быть на скриншоте 1 при отправке отчёта. */
        String report1Prompt,
        /** Что должно быть на скриншоте 2 (после принятия первого). */
        String report2Prompt,
        String stage1Banner,
        String stage2WaitBanner,
        String stage2PayBanner,
        String stage3Banner,
        String stage4Banner,
        String finalBanner,
        /** {подпись кнопки, текст игроку} - «вернуть на доработку». */
        String[][] returnReasons,
        /** {подпись кнопки, текст игроку} - «отклонить окончательно». */
        String[][] finalReasons) {

    /** Дата выплаты банка: с 25-го числа следующего месяца или через bankPayDays дней после покупки. */
    public LocalDate bankDate(LocalDate actionDay) {
        return bankPayDays > 0 ? actionDay.plusDays(bankPayDays) : actionDay.plusMonths(1).withDayOfMonth(25);
    }
}
