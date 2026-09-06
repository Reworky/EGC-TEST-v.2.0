package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.ExcTransaction;
import ru.gamebot.platform.domain.repository.ExcTransactionRepository;

@Service
@RequiredArgsConstructor
public class ExcTransactionService {

    private final ExcTransactionRepository repo;

    public static final String QUEST      = "QUEST";
    public static final String BONUS      = "BONUS";
    public static final String DEBIT      = "DEBIT";
    public static final String REFERRAL   = "REFERRAL";
    public static final String DAILY      = "DAILY";
    public static final String SHOP_BUY   = "SHOP_BUY";
    public static final String SHOP_REFUND= "SHOP_REFUND";
    public static final String SINK       = "SINK";
    public static final String TOURNAMENT = "TOURNAMENT";
    public static final String LEAGUE     = "LEAGUE";
    public static final String POLL       = "POLL";
    public static final String SEASON     = "SEASON";
    public static final String COUNCIL    = "COUNCIL";
    public static final String WITHDRAWAL = "WITHDRAWAL";
    public static final String TRANSFER       = "TRANSFER";
    public static final String WELCOME_BONUS  = "WELCOME_BONUS";
    public static final String CONFISCATE     = "CONFISCATE";
    public static final String AD_REWARD      = "AD_REWARD";
    /** Разовый бонус ПРИГЛАШЁННОМУ за вступление по ссылке — намеренно ОТДЕЛЬНЫЙ тип от REFERRAL:
     *  findReferralEarningsRankingBetween считает только REFERRAL (заработок самого реферера — инстант-бонус
     *  рефереру + 3% с квестов), иначе в "топ рефереров" попадали люди, которые сами никого не пригласили,
     *  а просто недавно присоединились по чьей-то ссылке (баг найден 2026-09-06 — все в топ-5 показывали 👥 0). */
    public static final String REFERRAL_WELCOME = "REFERRAL_WELCOME";
    /** Выплата призового пула топ-5 рефереров недели — тоже ОТДЕЛЬНЫЙ тип от REFERRAL: этот перевод создаётся
     *  ровно в 00:00 понедельника (см. WeeklyResetScheduler), т.е. в первую же секунду НОВОЙ недели, и раньше
     *  засчитывался в её рейтинг раньше, чем игрок успевал хоть что-то заработать на этой неделе. */
    public static final String REFERRAL_PRIZE = "REFERRAL_PRIZE";

    @Transactional(propagation = Propagation.REQUIRED)
    public void log(AppUser user, long amount, String type, String description) {
        ExcTransaction tx = new ExcTransaction();
        tx.setUser(user);
        tx.setAmount(amount);
        tx.setType(type);
        tx.setDescription(description);
        // Вызывается всегда после того, как вызывающий код уже применил изменение к user.coins
        tx.setBalanceAfter(user.getCoins());
        repo.save(tx);
    }

    public List<ExcTransaction> getHistory(AppUser user, int page, int pageSize) {
        return repo.findByUserOrderByCreatedAtDesc(user, PageRequest.of(page, pageSize));
    }

    public long countAll(AppUser user) {
        return repo.countByUser(user);
    }

    public long sumEarnedSince(LocalDateTime since) {
        return repo.sumEarnedSince(since);
    }

    public List<Object[]> findReferralEarningsRankingBetween(LocalDateTime from, LocalDateTime to) {
        return repo.findReferralEarningsRankingBetween(from, to);
    }

    public static String typeLabel(String type) {
        return switch (type) {
            case QUEST      -> "🎯 Квест";
            case BONUS      -> "🎁 Бонус";
            case DEBIT      -> "➖ Списание";
            case REFERRAL   -> "🤝 Реферал";
            case DAILY      -> "📅 Ежедневный";
            case SHOP_BUY   -> "🛍️ Магазин";
            case SHOP_REFUND-> "↩️ Возврат";
            case SINK       -> "⚡ Предметы";
            case TOURNAMENT -> "🏆 Турнир";
            case LEAGUE     -> "🥇 Лига";
            case POLL       -> "🗳️ Опрос";
            case SEASON     -> "🎫 Battle Pass";
            case COUNCIL    -> "🛡️ Council";
            case WITHDRAWAL -> "💸 Вывод";
            case TRANSFER       -> "🔄 Перевод";
            case WELCOME_BONUS  -> "🎉 Приветственный бонус";
            case CONFISCATE     -> "🚫 Конфискация";
            case AD_REWARD      -> "🎬 За рекламу";
            case REFERRAL_WELCOME -> "🤝 Бонус за вступление";
            case REFERRAL_PRIZE   -> "🏆 Приз топ-рефереров";
            default             -> "📌 Прочее";
        };
    }
}
