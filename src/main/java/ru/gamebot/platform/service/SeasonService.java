package ru.gamebot.platform.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Season;
import ru.gamebot.platform.domain.repository.SeasonRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SeasonService {

    private final SeasonRepository seasonRepository;
    private final UserService userService;
    private final ExcTransactionService excTx;

    public Optional<Season> findCurrentSeason() {
        LocalDateTime now = LocalDateTime.now();
        return seasonRepository.findFirstByActiveTrueAndStartDateBeforeAndEndDateAfterOrderByCreatedAtDesc(now, now);
    }

    public List<Season> findAll() {
        return seasonRepository.findAllByOrderByCreatedAtDesc();
    }

    public Optional<Season> findById(Long id) {
        return seasonRepository.findById(id);
    }

    public boolean hasActivePass(AppUser user) {
        return user.getSeasonPassActiveUntil() != null
                && LocalDateTime.now().isBefore(user.getSeasonPassActiveUntil());
    }

    public record PurchaseResult(boolean success, String error) {}

    @Transactional
    public PurchaseResult purchase(AppUser user, Season season) {
        if (!season.isActive()) return new PurchaseResult(false, "Сезон недоступен.");
        if (hasActivePass(user)) return new PurchaseResult(false, "У вас уже активен Season Pass.");
        if (user.getCoins() < season.getPriceExc())
            return new PurchaseResult(false, "Недостаточно EXC. Нужно: " + season.getPriceExc());

        user.setCoins(user.getCoins() - season.getPriceExc());

        LocalDateTime until = season.getEndDate() != null
                ? season.getEndDate()
                : LocalDateTime.now().plusDays(30);
        user.setSeasonPassActiveUntil(until);

        userService.save(user);
        excTx.log(user, -season.getPriceExc(), ExcTransactionService.SEASON, "Battle Pass: " + season.getName());
        return new PurchaseResult(true, null);
    }

    @Transactional
    public Season create(String name, long priceExc, int xpBoostPercent, LocalDateTime startDate, LocalDateTime endDate) {
        Season s = new Season();
        s.setName(name);
        s.setPriceExc(priceExc);
        s.setXpBoostPercent(xpBoostPercent);
        s.setStartDate(startDate);
        s.setEndDate(endDate);
        s.setActive(true);
        s.setCreatedAt(LocalDateTime.now());
        return seasonRepository.save(s);
    }

    @Transactional
    public void deactivate(Long id) {
        seasonRepository.findById(id).ifPresent(s -> {
            s.setActive(false);
            seasonRepository.save(s);
        });
    }
}
