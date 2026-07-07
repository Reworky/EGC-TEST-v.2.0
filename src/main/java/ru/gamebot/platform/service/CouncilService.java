package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.CouncilMember;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.CouncilMemberRepository;

@Service
@RequiredArgsConstructor
public class CouncilService {

    public static final int MAX_SEATS = 50;
    public static final long PRICE_EXC = 10_000;
    public static final long REQUIRED_LEVEL = 6;
    public static final long REQUIRED_XP = 75_000;

    private final CouncilMemberRepository councilMemberRepository;
    private final AppUserRepository appUserRepository;
    private final UserService userService;
    private final ExcTransactionService excTx;

    public boolean isCouncilMember(AppUser user) {
        return councilMemberRepository.existsByUser(user);
    }

    public long availableSeats() {
        return MAX_SEATS - councilMemberRepository.count();
    }

    @Transactional
    public void joinCouncil(AppUser user) {
        if (councilMemberRepository.existsByUser(user)) {
            throw new IllegalStateException("Вы уже являетесь членом EGC Council.");
        }
        if (availableSeats() <= 0) {
            throw new IllegalStateException("Все места в EGC Council заняты. Следите за анонсами освобождения мест.");
        }
        if (userService.getLevelNumber(user.getXp()) < REQUIRED_LEVEL) {
            throw new IllegalStateException(
                    "Для вступления в EGC Council нужен уровень 6 «Герой EXPERIENCE» или выше.\n"
                    + "Ваш текущий уровень: " + userService.getLevelNumber(user.getXp()) + ".");
        }
        if (user.getCoins() < PRICE_EXC) {
            throw new IllegalStateException(
                    "Для вступления нужно " + PRICE_EXC + " EXC. У вас: " + user.getCoins() + " EXC.");
        }

        user.setCoins(user.getCoins() - PRICE_EXC);
        appUserRepository.save(user);
        excTx.log(user, -PRICE_EXC, ExcTransactionService.COUNCIL, "Вступление в EGC Council");

        CouncilMember member = new CouncilMember();
        member.setUser(user);
        member.setJoinedAt(LocalDateTime.now());
        councilMemberRepository.save(member);
    }
}
