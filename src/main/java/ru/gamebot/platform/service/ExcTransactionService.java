package ru.gamebot.platform.service;

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

    @Transactional(propagation = Propagation.REQUIRED)
    public void log(AppUser user, long amount, String type, String description) {
        ExcTransaction tx = new ExcTransaction();
        tx.setUser(user);
        tx.setAmount(amount);
        tx.setType(type);
        tx.setDescription(description);
        repo.save(tx);
    }

    public List<ExcTransaction> getHistory(AppUser user, int page, int pageSize) {
        return repo.findByUserOrderByCreatedAtDesc(user, PageRequest.of(page, pageSize));
    }

    public long countAll(AppUser user) {
        return repo.countByUser(user);
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
            default         -> "📌 Прочее";
        };
    }
}
