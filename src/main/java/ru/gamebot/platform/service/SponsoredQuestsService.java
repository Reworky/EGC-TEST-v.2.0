package ru.gamebot.platform.service;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.enums.SubmissionStatus;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.model.QuestSubmission;

/** Разделы спонсорских квестов и суммы «до N ₽» (15.11.2026): общий источник для мини-аппа; бот берёт список разделов и правило раздела отсюда же.
 *  Сумма считается персонально: квесты, уже выполненные игроком (последняя заявка одобрена), в неё не входят. */
@Service
@RequiredArgsConstructor
public class SponsoredQuestsService {

    /** Раздел: ключ, подпись кнопки, название в заголовке. Порядок - как на экране. */
    public record Category(String key, String label, String title) { }

    public static final List<Category> CATEGORIES = List.of(
            new Category("debit", "💳 Дебетовые карты", "Дебетовые карты"),
            new Category("games", "🎮 Игровые партнёры", "Игровые партнёры"),
            new Category("credit", "🏦 Кредитные карты", "Кредитные карты"),
            new Category("mobile", "📱 Мобильная связь", "Мобильная связь"),
            new Category("gigs", "💼 Подработка", "Подработка"));

    /** Раздел спонсорского квеста: партнёрские миссии банка - по ключу (Т-Мобайл - связь, остальные - дебетовые карты); остальные - по словам в названии
     *  («кредит», «подработ», «связь»/«SIM»/«тариф»/«Мегафон», «дебетов»); всё прочее (CPI-офферы, приложения, игры) - «Игровые партнёры». */
    public static String categoryOf(Quest q) {
        PartnerMission pm = PartnerMissions.forQuest(q);
        if (pm != null) return "tmobile".equals(pm.key()) ? "mobile" : "debit";
        String t = ((q.getTitle() == null ? "" : q.getTitle()) + " " + (q.getGameName() == null ? "" : q.getGameName())).toLowerCase(java.util.Locale.ROOT);
        if (t.contains("кредит")) return "credit";
        if (t.contains("подработ")) return "gigs";
        if (t.contains("связь") || t.contains("тариф") || t.contains("мегафон") || t.contains("оператор") || t.matches("(?s).*\\bsim\\b.*")) return "mobile";
        if (t.contains("дебетов")) return "debit";
        return "games";
    }

    private final QuestService questService;
    private final EksiPicksService eksiPicksService;

    public List<Quest> activeQuests() {
        return questService.findAllSponsored().stream().filter(Quest::isActive).toList();
    }

    /** Квесты, которые игрок ещё не выполнил (последняя заявка не одобрена). */
    public List<Quest> notDone(AppUser user, List<Quest> quests) {
        List<Quest> out = new ArrayList<>();
        for (Quest q : quests) {
            QuestSubmission latest = user == null ? null : questService.getLatestSubmission(user, q);
            if (latest != null && latest.getStatus() == SubmissionStatus.APPROVED) continue;
            out.add(q);
        }
        return out;
    }

    public static long bankRub(List<Quest> quests) {
        long sum = 0;
        for (Quest q : quests) {
            PartnerMission pm = PartnerMissions.forQuest(q);
            if (pm != null) sum += pm.bankRewardRub() + pm.clubRewardRub();
        }
        return sum;
    }

    public static boolean hasPremium(List<Quest> quests) {
        for (Quest q : quests) {
            PartnerMission pm = PartnerMissions.forQuest(q);
            if (pm != null && pm.bonusItem() != null && pm.bonusItem().contains("Premium")) return true;
        }
        return false;
    }

    /** Сумма «до N ₽» по всем активным квестам, которые игрок ещё не выполнил. */
    public long prizeRub(AppUser user) {
        return bankRub(notDone(user, activeQuests()));
    }

    public record CategoryView(String key, String label, String title, int count, long prizeRub, boolean premium) { }

    /** «🤝 Экси рекомендует»: партнёрские рекомендации без наград EXC. */
    public record PickView(String key, String title, String description, String buttonLabel, String url, String adLabel) { }

    public record Overview(long prizeRub, List<CategoryView> categories, List<PickView> picks) { }

    public Overview overview(AppUser user) {
        List<Quest> active = activeQuests();
        List<CategoryView> cats = new ArrayList<>();
        for (Category c : CATEGORIES) {
            List<Quest> inside = active.stream().filter(q -> c.key().equals(categoryOf(q))).toList();
            List<Quest> left = notDone(user, inside);
            cats.add(new CategoryView(c.key(), c.label(), c.title(), inside.size(), bankRub(left), hasPremium(left)));
        }
        List<PickView> picks = new ArrayList<>();
        if (user != null) {
            try {
                for (EksiPicksService.Pick p : eksiPicksService.forUser(user)) {
                    picks.add(new PickView(p.key(), p.title(), p.description(), p.buttonLabel(), p.url(), p.adLabel()));
                }
            } catch (Exception e) {
                // рекомендации не критичны для экрана разделов
            }
        }
        return new Overview(bankRub(notDone(user, active)), cats, picks);
    }
}
