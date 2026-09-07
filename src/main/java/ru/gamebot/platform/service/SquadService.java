package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Squad;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.SquadRepository;
import ru.gamebot.platform.event.SquadPrizeEvent;
import ru.gamebot.platform.event.SquadReferralBonusEvent;

@Slf4j
@Service
@RequiredArgsConstructor
public class SquadService {

    /** Практически безлимит на нынешнем масштабе проекта (~3600 игроков всего) — раньше было 5,
     *  снято ради вирусного роста по образцу крупных Telegram-сообществ/кланов (см. rewardTopSquad:
     *  недельный приз ограничен PRIZE_MAX_RECIPIENTS, а не размером отряда, поэтому рост отряда
     *  не увеличивает расходы клуба). */
    private static final int MAX_MEMBERS = 500;
    private static final int MIN_MEMBERS = 2;
    private static final long WEEKLY_PRIZE_POOL = 10_000L;
    /** Приз получают не "все участники", а топ-N по недельному XP — иначе при большом отряде
     *  целочисленное деление WEEKLY_PRIZE_POOL/members.size() молча схлопывается к 0 на человека.
     *  Для отрядов ≤10 человек (весь текущий состав игроков) поведение идентично старому. */
    private static final int PRIZE_MAX_RECIPIENTS = 10;
    private static final long REFERRAL_SQUAD_BONUS_POINTS = 100;
    private static final int REFERRAL_SQUAD_JOIN_WINDOW_DAYS = 7;

    private final SquadRepository squadRepository;
    private final AppUserRepository appUserRepository;
    private final ApplicationEventPublisher eventPublisher;

    public Optional<Squad> findById(Long id) {
        return squadRepository.findById(id);
    }

    public Optional<Squad> findByUser(AppUser user) {
        if (user.getSquadId() == null) return Optional.empty();
        return squadRepository.findById(user.getSquadId())
                .filter(s -> "ACTIVE".equals(s.getStatus()));
    }

    public List<AppUser> getMembers(Squad squad) {
        return appUserRepository.findAllBySquadId(squad.getId());
    }

    public long memberCount(Squad squad) {
        return appUserRepository.countBySquadId(squad.getId());
    }

    public long squadWeeklyXp(Squad squad) {
        return getMembers(squad).stream().mapToLong(AppUser::getWeeklyXp).sum() + squad.getWeeklyBonusPoints();
    }

    public boolean isNameTaken(String name) {
        return squadRepository.existsByNameIgnoreCase(name.trim());
    }

    @Transactional
    public Squad create(AppUser captain, String name) {
        if (captain.getSquadId() != null) {
            throw new IllegalStateException("Вы уже состоите в отряде.");
        }
        if (isNameTaken(name)) {
            throw new IllegalArgumentException("Отряд с таким названием уже существует.");
        }
        Squad squad = new Squad();
        squad.setName(name.trim());
        squad.setCaptainTelegramId(captain.getTelegramId());
        squad.setInviteCode(generateInviteCode());
        squad.setStatus("ACTIVE");
        squad.setCreatedAt(LocalDateTime.now());
        squad = squadRepository.save(squad);

        captain.setSquadId(squad.getId());
        appUserRepository.save(captain);
        return squad;
    }

    @Transactional
    public Squad join(AppUser user, Long squadId) {
        if (user.getSquadId() != null) {
            throw new IllegalStateException("Вы уже состоите в отряде. Покиньте его перед вступлением.");
        }
        Squad squad = squadRepository.findById(squadId)
                .orElseThrow(() -> new IllegalArgumentException("Отряд не найден."));
        if (!"ACTIVE".equals(squad.getStatus())) {
            throw new IllegalStateException("Этот отряд расформирован.");
        }
        long count = memberCount(squad);
        if (count >= MAX_MEMBERS) {
            throw new IllegalStateException("Отряд уже заполнен (" + MAX_MEMBERS + "/" + MAX_MEMBERS + " игроков).");
        }
        user.setSquadId(squad.getId());
        appUserRepository.save(user);
        awardReferralSquadBonus(user, squad);
        return squad;
    }

    /** Модуль "Реферал усиливает Отряд" (максимизация рефералки, Модуль 2): если вступивший был приглашён
     *  кем-то и вступает именно в отряд ПРИГЛАСИВШЕГО (не в любой отряд вообще) в течение
     *  {@link #REFERRAL_SQUAD_JOIN_WINDOW_DAYS} дней с момента СВОЕЙ регистрации — отряду начисляются
     *  бонусные очки к недельному рейтингу и все участники получают уведомление. */
    private void awardReferralSquadBonus(AppUser invitedUser, Squad squad) {
        Long referrerTelegramId = invitedUser.getReferredByTelegramId();
        if (referrerTelegramId == null) return;
        if (invitedUser.getCreatedAt() == null
                || invitedUser.getCreatedAt().plusDays(REFERRAL_SQUAD_JOIN_WINDOW_DAYS).isBefore(LocalDateTime.now())) {
            return; // окно "за счёт приглашения" истекло
        }
        AppUser referrer = appUserRepository.findByTelegramId(referrerTelegramId).orElse(null);
        if (referrer == null || referrer.getSquadId() == null || !referrer.getSquadId().equals(squad.getId())) {
            return; // вступил не в отряд именно пригласившего
        }
        squad.setWeeklyBonusPoints(squad.getWeeklyBonusPoints() + REFERRAL_SQUAD_BONUS_POINTS);
        squadRepository.save(squad);
        List<AppUser> members = getMembers(squad);
        eventPublisher.publishEvent(new SquadReferralBonusEvent(this, squad, members, invitedUser, REFERRAL_SQUAD_BONUS_POINTS));
    }

    /** Раз в неделю (см. WeeklyResetScheduler), после того как rewardTopSquad() прочитал итоговый счёт —
     *  иначе бонусные очки накапливались бы бессрочно вместо действия только на текущую неделю. */
    @Transactional
    public void resetWeeklyBonusPoints() {
        List<Squad> all = squadRepository.findAll();
        for (Squad s : all) {
            s.setWeeklyBonusPoints(0);
        }
        squadRepository.saveAll(all);
    }

    @Transactional
    public Squad joinByInviteCode(AppUser user, String code) {
        Squad squad = squadRepository.findByInviteCode(code.trim().toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("Отряд с таким кодом не найден."));
        return join(user, squad.getId());
    }

    @Transactional
    public void leave(AppUser user) {
        if (user.getSquadId() == null) {
            throw new IllegalStateException("Вы не состоите ни в одном отряде.");
        }
        Squad squad = squadRepository.findById(user.getSquadId()).orElse(null);
        user.setSquadId(null);
        appUserRepository.save(user);

        if (squad != null && user.getTelegramId().equals(squad.getCaptainTelegramId())) {
            // Captain left — transfer to next member or disband
            List<AppUser> remaining = appUserRepository.findAllBySquadId(squad.getId());
            if (remaining.isEmpty()) {
                squad.setStatus("DISBANDED");
                squadRepository.save(squad);
            } else {
                squad.setCaptainTelegramId(remaining.get(0).getTelegramId());
                squadRepository.save(squad);
            }
        }
    }

    @Transactional
    public void disband(AppUser captain) {
        if (captain.getSquadId() == null) {
            throw new IllegalStateException("Вы не состоите в отряде.");
        }
        Squad squad = squadRepository.findById(captain.getSquadId())
                .orElseThrow(() -> new IllegalArgumentException("Отряд не найден."));
        if (!captain.getTelegramId().equals(squad.getCaptainTelegramId())) {
            throw new IllegalStateException("Только капитан может расформировать отряд.");
        }
        List<AppUser> members = appUserRepository.findAllBySquadId(squad.getId());
        for (AppUser member : members) {
            member.setSquadId(null);
        }
        appUserRepository.saveAll(members);
        squad.setStatus("DISBANDED");
        squadRepository.save(squad);
    }

    @Transactional
    public void kick(AppUser captain, Long memberTelegramId) {
        if (captain.getSquadId() == null) throw new IllegalStateException("Вы не в отряде.");
        Squad squad = squadRepository.findById(captain.getSquadId())
                .orElseThrow(() -> new IllegalArgumentException("Отряд не найден."));
        if (!captain.getTelegramId().equals(squad.getCaptainTelegramId())) {
            throw new IllegalStateException("Только капитан может исключать участников.");
        }
        if (captain.getTelegramId().equals(memberTelegramId)) {
            throw new IllegalStateException("Нельзя исключить самого себя.");
        }
        AppUser member = appUserRepository.findByTelegramId(memberTelegramId)
                .orElseThrow(() -> new IllegalArgumentException("Игрок не найден."));
        if (!squad.getId().equals(member.getSquadId())) {
            throw new IllegalStateException("Этот игрок не состоит в вашем отряде.");
        }
        member.setSquadId(null);
        appUserRepository.save(member);
    }

    /** Returns top-20 active squads sorted by weeklyXp descending. */
    public List<SquadRankEntry> getLeaderboard() {
        List<Squad> active = squadRepository.findAllByStatus("ACTIVE");
        return active.stream()
                .map(s -> {
                    long xp = squadWeeklyXp(s);
                    long count = memberCount(s);
                    return new SquadRankEntry(s, xp, count);
                })
                .filter(e -> e.weeklyXp() > 0)
                .sorted(Comparator.comparingLong(SquadRankEntry::weeklyXp).reversed())
                .limit(20)
                .toList();
    }

    /** Called before weekly XP reset. Pays 10 000 EXC split equally to members of the top squad. */
    @Transactional
    public void rewardTopSquad() {
        List<SquadRankEntry> leaderboard = getLeaderboard();
        if (leaderboard.isEmpty()) return;

        SquadRankEntry top = leaderboard.get(0);
        if (top.memberCount() < MIN_MEMBERS) return; // need at least 2 to qualify

        List<AppUser> members = appUserRepository.findAllBySquadId(top.squad().getId());
        if (members.isEmpty()) return;

        List<AppUser> sorted = members.stream()
                .sorted(Comparator.comparingLong(AppUser::getWeeklyXp).reversed())
                .toList();
        int payoutCount = Math.min(sorted.size(), PRIZE_MAX_RECIPIENTS);
        List<AppUser> winners = sorted.subList(0, payoutCount);
        long prizePerMember = WEEKLY_PRIZE_POOL / payoutCount;
        for (AppUser member : winners) {
            member.setCoins(member.getCoins() + prizePerMember);
        }
        appUserRepository.saveAll(winners);
        log.info("Squad weekly prize: {} EXC each to top {} of {} members of squad '{}' (total XP: {})",
                prizePerMember, payoutCount, members.size(), top.squad().getName(), top.weeklyXp());

        eventPublisher.publishEvent(new SquadPrizeEvent(this, top.squad(), winners, prizePerMember, top.weeklyXp()));
    }

    @Transactional
    public String refreshInviteCode(Squad squad) {
        squad.setInviteCode(generateInviteCode());
        squadRepository.save(squad);
        return squad.getInviteCode();
    }

    private String generateInviteCode() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    public record SquadRankEntry(Squad squad, long weeklyXp, long memberCount) {}
}
