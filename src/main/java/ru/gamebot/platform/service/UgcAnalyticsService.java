package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.enums.SubmissionStatus;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.QuestSubmission;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;

/**
 * Вкладка аналитики «UGC и приход с роликов» (21.11.2026): сколько роликов взяли и одобрили, сколько это стоит (EXC по курсу на момент одобрения + EXCASH пилота),
 * и сколько людей привели авторы по своей реферальной ссылке (t.me/бот?start=ref_<ID автора>). Ссылка у автора одна на все ролики, поэтому приход считается по автору:
 * новички, зарегистрированные после первого взятого им UGC-квеста, у которых referredByTelegramId = автор. Авторы без реферальной ссылки в ролике здесь не видны.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UgcAnalyticsService {

    private static final Locale RU = Locale.forLanguageTag("ru");
    private static final int TABLE_ROWS = 15;

    private final QuestSubmissionRepository submissionRepo;
    private final AppUserRepository userRepo;
    private final UgcPilotService ugcPilotService;

    private record Spend(long rubExc, long excash, long exc) {}

    private static boolean pilotPaid(QuestSubmission s) {
        return UgcPilotService.HELD.equals(s.getHeldStatus()) || UgcPilotService.RELEASED.equals(s.getHeldStatus());
    }

    private static Spend spendOf(List<QuestSubmission> list) {
        long rubExc = 0, excash = 0, exc = 0;
        for (QuestSubmission s : list) {
            long awarded = s.getAwardedCoins() == null ? 0 : s.getAwardedCoins();
            long held = pilotPaid(s) && s.getHeldCoins() != null ? s.getHeldCoins() : 0;
            excash += held;
            exc += Math.max(0, awarded - held);
            rubExc += s.getFixedRubValue() == null ? 0 : s.getFixedRubValue();
        }
        return new Spend(rubExc, excash, exc);
    }

    private static double rubOf(Spend sp) {
        return sp.rubExc() + sp.excash() / 100.0;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String cut(String v, int n) {
        if (v == null) return "";
        return v.length() <= n ? v : v.substring(0, n - 1) + "…";
    }

    private static String num(long n) {
        return String.format(RU, "%,d", n).replace(',', ' ').replace(' ', ' ');
    }

    /** Заполняет вкладку: строки с метриками периода и два текстовых блока (авторы и пояснения). */
    public void fill(AnalyticsService.Period p, AnalyticsService.Period pv, List<AnalyticsService.Line> out, List<String> extras) {
        List<QuestSubmission> taken = submissionRepo.findUgcCreatedBetween(p.from(), p.to());
        List<QuestSubmission> takenPrev = submissionRepo.findUgcCreatedBetween(pv.from(), pv.to());
        List<QuestSubmission> approved = submissionRepo.findUgcApprovedBetween(p.from(), p.to());
        List<QuestSubmission> approvedPrev = submissionRepo.findUgcApprovedBetween(pv.from(), pv.to());

        out.add(new AnalyticsService.Line("ugc_taken", "Взяли UGC-квест (заявок)", "", (double) taken.size(), (double) takenPrev.size(),
                taken.stream().map(s -> s.getUser().getId()).distinct().count() + " игроков", false));
        long pending = taken.stream().filter(s -> s.getStatus() == SubmissionStatus.PENDING).count();
        out.add(new AnalyticsService.Line("ugc_pending", "  из них сейчас на проверке", "", (double) pending, null, null, false));
        out.add(new AnalyticsService.Line("ugc_approved", "Одобрено роликов", "", (double) approved.size(), (double) approvedPrev.size(), null, false));

        Spend sp = spendOf(approved);
        Spend spPrev = spendOf(approvedPrev);
        out.add(new AnalyticsService.Line("ugc_rub", "Расход на UGC", "₽", rubOf(sp), rubOf(spPrev), "EXC по курсу на момент одобрения + EXCASH (100 = 1 ₽)", true));
        out.add(new AnalyticsService.Line("ugc_excash", "  EXCASH (пилот)", "EXCASH", (double) sp.excash(), (double) spPrev.excash(), null, false));
        out.add(new AnalyticsService.Line("ugc_exc", "  EXC", "EXC", (double) sp.exc(), (double) spPrev.exc(), null, false));

        // Авторы одобренных роликов периода: приход по их реферальной ссылке после первого взятого ими UGC-квеста
        Map<Long, List<QuestSubmission>> byUser = new LinkedHashMap<>();
        for (QuestSubmission s : approved) byUser.computeIfAbsent(s.getUser().getId(), k -> new ArrayList<>()).add(s);
        record Creator(AppUser user, int videos, double rub, long excash, long joined, long activated, long quested) {}
        List<Creator> creators = new ArrayList<>();
        long totalJoined = 0, totalActivated = 0, totalQuested = 0;
        for (List<QuestSubmission> list : byUser.values()) {
            AppUser u = list.get(0).getUser();
            Spend s = spendOf(list);
            long joined = 0, activated = 0, quested = 0;
            try {
                LocalDateTime since = submissionRepo.findFirstUgcApprovedTakenAt(u).orElse(null);
                if (since != null && u.getTelegramId() != null) {
                    joined = userRepo.countByReferredByTelegramIdAndCreatedAtGreaterThanEqual(u.getTelegramId(), since);
                    activated = userRepo.countByReferredByTelegramIdAndCreatedAtGreaterThanEqualAndRegistrationCompletedTrue(u.getTelegramId(), since);
                    quested = userRepo.countByReferredByTelegramIdAndCreatedAtGreaterThanEqualAndCompletedQuestsGreaterThan(u.getTelegramId(), since, 0);
                }
            } catch (Exception e) {
                log.warn("UGC analytics: referrals count failed for user {}", u.getId(), e);
            }
            totalJoined += joined;
            totalActivated += activated;
            totalQuested += quested;
            creators.add(new Creator(u, list.size(), rubOf(s), s.excash(), joined, activated, quested));
        }
        creators.sort((a, b) -> Double.compare(b.rub(), a.rub()));

        out.add(new AnalyticsService.Line("ugc_ref_joined", "Пришло по ссылкам авторов", "", (double) totalJoined, null,
                "авторы роликов периода, регистрации после их первого UGC-квеста", false));
        out.add(new AnalyticsService.Line("ugc_ref_activated", "  из них активировали аккаунт", "", (double) totalActivated, null, "подписались на канал", false));
        out.add(new AnalyticsService.Line("ugc_ref_quest", "  из них сделали первый квест", "", (double) totalQuested, null, null, false));
        double cpa = totalActivated > 0 ? rubOf(sp) / totalActivated : 0;
        out.add(new AnalyticsService.Line("ugc_cpa", "Цена активированного игрока", "₽", totalActivated > 0 ? cpa : null, null,
                totalActivated > 0 ? "расход на UGC ÷ активированные по ссылкам авторов" : "пока нет активированных по ссылкам авторов", true));

        StringBuilder t = new StringBuilder("<b>Авторы одобренных роликов</b>\n<pre>автор          вид  расход  приш акт квест\n");
        int shown = 0;
        for (Creator c : creators) {
            if (shown++ >= TABLE_ROWS) break;
            String nick = c.user().getNickname() != null && !c.user().getNickname().isBlank() ? c.user().getNickname()
                    : (c.user().getTelegramUsername() != null && !c.user().getTelegramUsername().isBlank() ? "@" + c.user().getTelegramUsername() : "id" + c.user().getTelegramId());
            t.append(String.format(RU, "%-14s %3d %6s ₽ %5d %3d %5d%n", esc(cut(nick, 14)), c.videos(), num(Math.round(c.rub())), c.joined(), c.activated(), c.quested()));
        }
        if (creators.isEmpty()) t.append("нет одобренных роликов в периоде\n");
        t.append("</pre><i>вид - одобрено роликов; расход - EXC по курсу при одобрении + EXCASH; приш - зарегистрировались по ссылке автора; акт - активировали аккаунт; квест - сделали хотя бы один квест.</i>");
        extras.add(t.toString());

        extras.add("<b>Как читать</b>\nСсылка у автора одна на все его ролики, поэтому приход считается по автору, а не по ролику. "
                + "Если автор не вставил реферальную ссылку, его приход здесь не виден: смотрите рост новичков по дням на вкладке «Пользователи и приток». "
                + "Реферал, зарегистрированный до первого UGC-квеста автора, не считается. Главное число пилота - «Цена активированного игрока».");
        extras.add(ugcPilotService.statusHtml());
    }
}
