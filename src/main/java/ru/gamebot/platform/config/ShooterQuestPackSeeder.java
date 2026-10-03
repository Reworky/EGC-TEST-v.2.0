package ru.gamebot.platform.config;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.enums.Cs2VerifyType;
import ru.gamebot.platform.domain.enums.DotaVerifyType;
import ru.gamebot.platform.domain.enums.PubgVerifyType;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.model.QuestPack;
import ru.gamebot.platform.domain.repository.QuestRepository;
import ru.gamebot.platform.service.QuestPackService;

/**
 * Пачки квестов CS2, Dota 2, PUBG PC и PUBG Mobile («Сезон А» и «Сезон Б», согласовано 2026-10-03). Устройство и
 * правила — как у BrawlQuestPackSeeder: награда только при создании квеста, названия уникальны среди всех квестов
 * игры, «Основная» заводится первой. CS2/Dota 2/PUBG PC проверяются автоматически (новизну дают другие пороги),
 * PUBG Mobile — по скриншоту (автоматизировать нельзя, решение владельца), как и текущие квесты этой игры.
 */
@Slf4j
@Component
@Order(5)
@RequiredArgsConstructor
public class ShooterQuestPackSeeder implements CommandLineRunner {

    private static final String CS2 = "CS2";
    private static final String DOTA = "Dota 2";
    private static final String PUBG_PC = "PUBG PC";
    private static final String PUBG_MOBILE = "PUBG Mobile";

    private static final String CS2_REQ = "Ничего отправлять не нужно — прогресс проверяется автоматически через официальный "
            + "Steam Web API, награда зачислится сама после выполнения условия.";
    private static final String DOTA_REQ = "Ничего отправлять не нужно — прогресс проверяется автоматически по статистике матчей "
            + "(нужен привязанный Steam-аккаунт), награда зачислится сама после выполнения условия.";
    private static final String PUBG_REQ = "Ничего отправлять не нужно — прогресс проверяется автоматически через официальный "
            + "PUBG API, награда зачислится сама после выполнения условия.";
    private static final String AUTO = "Прогресс отслеживается автоматически, ничего сообщать не нужно.";
    private static final String AUTO_AFTER_MATCH = "Прогресс отслеживается автоматически после завершения матча, ничего сообщать не нужно.";

    private final QuestRepository questRepository;
    private final QuestPackService questPackService;

    private record Def(String title, String label, String platform, int days, long coins, String description,
                       String instruction, String requirements, Consumer<Quest> verify) {}

    @Override
    @Transactional
    public void run(String... args) {
        seedGame(CS2, cs2PackA(), cs2PackB());
        seedGame(DOTA, dotaPackA(), dotaPackB());
        seedGame(PUBG_PC, pubgPcPackA(), pubgPcPackB());
        seedGame(PUBG_MOBILE, pubgMobilePackA(), pubgMobilePackB());
    }

    private void seedGame(String game, List<Def> packA, List<Def> packB) {
        try {
            questPackService.ensureMainPack(game);
            seedPack(game, "Сезон А", packA);
            seedPack(game, "Сезон Б", packB);
        } catch (Exception e) {
            log.error("[ShooterQuestPackSeeder] {} failed", game, e);
        }
    }

    private void seedPack(String game, String packName, List<Def> defs) {
        QuestPack pack = questPackService.getOrCreatePack(game, packName);
        for (Def d : defs) {
            Quest q = questRepository.findFirstByTitleAndGameName(d.title(), game).orElse(null);
            if (q == null) {
                q = new Quest();
                q.setTitle(d.title());
                q.setGameName(game);
                q.setCategory(null);
                q.setRewardXp(d.coins() >= 2500 ? 100 : 50);
                q.setRewardCoins(d.coins());
                q.setCouncilOnly(false);
                q.setCreatedAt(LocalDateTime.now());
                q.setActive(pack.isActive());
                q.setPackSuspended(!pack.isActive());
                log.info("[ShooterQuestPackSeeder] Created '{}' ({}) in pack '{}'", d.title(), game, packName);
            }
            q.setPackId(pack.getId());
            q.setPlatform(d.platform());
            q.setDurationDays(d.days());
            q.setDurationText(d.days() + " дней");
            q.setDescription(d.description());
            q.setInstruction(d.instruction());
            q.setRequirements(d.requirements());
            q.setShortLabel(d.label());
            d.verify().accept(q);
            questRepository.save(q);
        }
    }

    private static String fmt(int n) {
        String s = String.valueOf(n);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) {
                sb.append(' ');
            }
            sb.append(s.charAt(i));
        }
        return sb.toString();
    }

    // ── CS2 ─────────────────────────────────────────────────────────────────────
    private List<Def> cs2PackA() {
        List<Def> l = new ArrayList<>();
        l.add(cs2("Сыграй 5 матчей в любом режиме", "5 матчей", Cs2VerifyType.MATCHES_PLAYED, 5, 3, 2000));
        l.add(cs2("Победи в двух матчах", "Победа×2", Cs2VerifyType.WINS, 2, 5, 2400));
        l.add(cs2("Набери 12 убийств за матч", "12 убийств/матч", Cs2VerifyType.LAST_MATCH_KILLS, 12, 3, 1500));
        l.add(cs2("Набери 25 убийств за матч", "25 убийств/матч", Cs2VerifyType.LAST_MATCH_KILLS, 25, 5, 3000));
        l.add(cs2("Умри не более 7 раз за матч", "≤7 смертей/матч", Cs2VerifyType.LAST_MATCH_DEATHS_MAX, 7, 3, 2000));
        l.add(cs2("Получи 2 звезды MVP за матч", "2 MVP/матч", Cs2VerifyType.LAST_MATCH_MVPS, 2, 3, 1500));
        l.add(cs2("Получи 4 звезды MVP за матч", "4 MVP/матч", Cs2VerifyType.LAST_MATCH_MVPS, 4, 5, 2200));
        l.add(cs2("Набери счёт 35+ за матч", "Счёт 35+", Cs2VerifyType.LAST_MATCH_SCORE, 35, 3, 1800));
        l.add(cs2("Набери 50 убийств выстрелом в голову", "50 хедшотов", Cs2VerifyType.HEADSHOTS, 50, 7, 2500));
        l.add(cs2("Заложи бомбу 5 раз", "5 закладок бомбы", Cs2VerifyType.BOMBS_PLANTED, 5, 7, 1800));
        l.add(cs2("Обезвредь бомбу 2 раза", "2 обезвреживания", Cs2VerifyType.BOMBS_DEFUSED, 2, 7, 2000));
        l.add(cs2("Заверши матч с K/D выше 1.5", "K/D выше 1.5", Cs2VerifyType.LAST_MATCH_KD_RATIO, 150, 5, 2000));
        return l;
    }

    private List<Def> cs2PackB() {
        List<Def> l = new ArrayList<>();
        l.add(cs2("Сыграй 4 матча в любом режиме", "4 матча", Cs2VerifyType.MATCHES_PLAYED, 4, 3, 1800));
        l.add(cs2("Победи в трёх матчах", "Победа×3", Cs2VerifyType.WINS, 3, 7, 3000));
        l.add(cs2("Набери 8 убийств за матч", "8 убийств/матч", Cs2VerifyType.LAST_MATCH_KILLS, 8, 3, 1500));
        l.add(cs2("Набери 22 убийства за матч", "22 убийства/матч", Cs2VerifyType.LAST_MATCH_KILLS, 22, 5, 2600));
        l.add(cs2("Набери 35 убийств за матч", "35 убийств/матч", Cs2VerifyType.LAST_MATCH_KILLS, 35, 7, 3000));
        l.add(cs2("Умри не более 9 раз за матч", "≤9 смертей/матч", Cs2VerifyType.LAST_MATCH_DEATHS_MAX, 9, 3, 1500));
        l.add(cs2("Умри не более 4 раз за матч", "≤4 смерти/матч", Cs2VerifyType.LAST_MATCH_DEATHS_MAX, 4, 7, 2800));
        l.add(cs2("Получи 5 звёзд MVP за матч", "5 MVP/матч", Cs2VerifyType.LAST_MATCH_MVPS, 5, 5, 2500));
        l.add(cs2("Набери счёт 30+ за матч", "Счёт 30+", Cs2VerifyType.LAST_MATCH_SCORE, 30, 3, 1500));
        l.add(cs2("Набери счёт 55+ за матч", "Счёт 55+", Cs2VerifyType.LAST_MATCH_SCORE, 55, 7, 2800));
        l.add(cs2("Набери 15 убийств выстрелом в голову", "15 хедшотов", Cs2VerifyType.HEADSHOTS, 15, 5, 1500));
        l.add(cs2("Заложи бомбу 12 раз", "12 закладок бомбы", Cs2VerifyType.BOMBS_PLANTED, 12, 7, 3000));
        l.add(cs2("Обезвредь бомбу 4 раза", "4 обезвреживания", Cs2VerifyType.BOMBS_DEFUSED, 4, 7, 3000));
        l.add(cs2("Заверши матч с K/D выше 2.5", "K/D выше 2.5", Cs2VerifyType.LAST_MATCH_KD_RATIO, 250, 7, 2800));
        return l;
    }

    private static Def cs2(String title, String label, Cs2VerifyType type, int target, int days, long coins) {
        boolean lastMatch = type.name().startsWith("LAST_MATCH");
        String description = switch (type) {
            case MATCHES_PLAYED -> "Сыграй " + target + " матчей в CS2 в любом режиме с момента взятия квеста — прогресс считается автоматически.";
            case WINS -> "Выиграй " + target + (target < 5 ? " матча" : " матчей") + " в CS2 с момента взятия квеста — прогресс считается автоматически.";
            case LAST_MATCH_KILLS -> "Набери " + target + " и более убийств за один матч CS2 — прогресс считается автоматически по статистике последнего сыгранного матча.";
            case LAST_MATCH_DEATHS_MAX -> "Заверши матч CS2, умерев не более " + target + " раз — прогресс считается автоматически по статистике последнего сыгранного матча.";
            case LAST_MATCH_MVPS -> "Получи " + target + " и более звёзд MVP за один матч CS2 — прогресс считается автоматически по статистике последнего сыгранного матча.";
            case LAST_MATCH_SCORE -> "Набери общий счёт " + target + " и выше за один матч CS2 — прогресс считается автоматически по статистике последнего сыгранного матча.";
            case LAST_MATCH_KD_RATIO -> "Заверши матч CS2 с соотношением убийств к смертям (K/D) выше " + String.format(java.util.Locale.ROOT, "%.1f", target / 100.0)
                    + " — прогресс считается автоматически по статистике последнего сыгранного матча.";
            case HEADSHOTS -> "Набери " + target + " убийств выстрелом в голову в CS2 с момента взятия квеста — прогресс считается автоматически.";
            case BOMBS_PLANTED -> "Заложи бомбу " + target + " раз в CS2 с момента взятия квеста — прогресс считается автоматически.";
            case BOMBS_DEFUSED -> "Обезвредь бомбу " + target + " раз в CS2 с момента взятия квеста — прогресс считается автоматически.";
            default -> title;
        };
        String how = switch (type) {
            case MATCHES_PLAYED -> "Играй матчи в любом режиме до конца. ";
            case WINS -> "Играй матчи в любом режиме и побеждай. ";
            case LAST_MATCH_DEATHS_MAX -> "Играй осторожно и избегай лишних смертей. ";
            case LAST_MATCH_KD_RATIO -> "Играй результативно и избегай лишних смертей. ";
            case HEADSHOTS -> "Целься в голову — считаются убийства выстрелом в голову из разных матчей. ";
            case BOMBS_PLANTED -> "Играй за атакующих в режимах с бомбой и закладывай её. ";
            case BOMBS_DEFUSED -> "Играй за защищающихся в режимах с бомбой и успевай её обезвредить. ";
            default -> "Играй результативно и активно. ";
        };
        return new Def(title, label, "PC (Steam)", days, coins, description, how + (lastMatch ? AUTO_AFTER_MATCH : AUTO), CS2_REQ,
                q -> {
                    q.setCs2VerifyType(type);
                    q.setCs2TargetCount(target);
                });
    }

    // ── Dota 2 ──────────────────────────────────────────────────────────────────
    private List<Def> dotaPackA() {
        List<Def> l = new ArrayList<>();
        l.add(dota("Набери 6 убийств за матч", "6 убийств/матч", DotaVerifyType.KILLS, 6, 3, 1500));
        l.add(dota("Набери 12 убийств за матч", "12 убийств/матч", DotaVerifyType.KILLS, 12, 5, 2200));
        l.add(dota("Сделай 7 ассистов за матч", "7 ассистов/матч", DotaVerifyType.ASSISTS, 7, 3, 1500));
        l.add(dota("Сделай 12 ассистов за матч", "12 ассистов/матч", DotaVerifyType.ASSISTS, 12, 5, 2500));
        l.add(dota("Умри не более 10 раз за матч", "≤10 смертей/матч", DotaVerifyType.DEATHS_MAX, 10, 3, 1500));
        l.add(dota("Умри не более 6 раз за матч", "≤6 смертей/матч", DotaVerifyType.DEATHS_MAX, 6, 5, 2200));
        l.add(dota("Заработай 10,000 золота за матч", "10к золота/матч", DotaVerifyType.GOLD, 10000, 3, 1500));
        l.add(dota("Заработай 20,000 золота за матч", "20к золота/матч", DotaVerifyType.GOLD, 20000, 5, 2500));
        l.add(dota("Достигни 18 уровня героя за матч", "18 ур. героя", DotaVerifyType.HERO_LEVEL, 18, 3, 1500));
        l.add(dota("Достигни 22 уровня героя за матч", "22 ур. героя", DotaVerifyType.HERO_LEVEL, 22, 5, 2500));
        l.add(dota("Сыграй матч длительностью 25+ минут", "Матч 25+ мин", DotaVerifyType.DURATION_MINUTES, 25, 3, 1500));
        l.add(dota("Сыграй матч длительностью 40+ минут", "Матч 40+ мин", DotaVerifyType.DURATION_MINUTES, 40, 5, 2500));
        return l;
    }

    private List<Def> dotaPackB() {
        List<Def> l = new ArrayList<>();
        l.add(dota("Набери 10 убийств за матч", "10 убийств/матч", DotaVerifyType.KILLS, 10, 3, 2000));
        l.add(dota("Набери 20 убийств за матч", "20 убийств/матч", DotaVerifyType.KILLS, 20, 5, 2800));
        l.add(dota("Сделай 9 ассистов за матч", "9 ассистов/матч", DotaVerifyType.ASSISTS, 9, 3, 1800));
        l.add(dota("Сделай 20 ассистов за матч", "20 ассистов/матч", DotaVerifyType.ASSISTS, 20, 5, 2800));
        l.add(dota("Умри не более 7 раз за матч", "≤7 смертей/матч", DotaVerifyType.DEATHS_MAX, 7, 3, 1800));
        l.add(dota("Умри не более 4 раз за матч", "≤4 смерти/матч", DotaVerifyType.DEATHS_MAX, 4, 5, 2800));
        l.add(dota("Заработай 12,000 золота за матч", "12к золота/матч", DotaVerifyType.GOLD, 12000, 3, 1800));
        l.add(dota("Заработай 30,000 золота за матч", "30к золота/матч", DotaVerifyType.GOLD, 30000, 5, 2800));
        l.add(dota("Достигни 15 уровня героя за матч", "15 ур. героя", DotaVerifyType.HERO_LEVEL, 15, 3, 1500));
        l.add(dota("Достигни 30 уровня героя за матч", "30 ур. героя", DotaVerifyType.HERO_LEVEL, 30, 5, 2800));
        l.add(dota("Сыграй матч длительностью 35+ минут", "Матч 35+ мин", DotaVerifyType.DURATION_MINUTES, 35, 3, 2000));
        l.add(dota("Сыграй матч длительностью 50+ минут", "Матч 50+ мин", DotaVerifyType.DURATION_MINUTES, 50, 5, 2800));
        return l;
    }

    private static Def dota(String title, String label, DotaVerifyType type, int target, int days, long coins) {
        String description = switch (type) {
            case KILLS -> "Набери " + target + " и более убийств за один матч Dota 2 — прогресс считается автоматически по статистике последнего сыгранного матча.";
            case ASSISTS -> "Сделай " + target + " и более ассистов за один матч Dota 2 — прогресс считается автоматически по статистике последнего сыгранного матча.";
            case DEATHS_MAX -> "Заверши матч Dota 2, умерев не более " + target + " раз — прогресс считается автоматически по статистике последнего сыгранного матча.";
            case GOLD -> "Заработай " + fmt(target) + " и более золота за один матч Dota 2 — прогресс считается автоматически по статистике последнего сыгранного матча.";
            case HERO_LEVEL -> "Достигни " + target + " уровня героя за один матч Dota 2 — прогресс считается автоматически по статистике последнего сыгранного матча.";
            case DURATION_MINUTES -> "Сыграй полный матч в Dota 2 длительностью минимум " + target + " минут — победа или поражение не важны.";
        };
        return new Def(title, label, "PC (Steam)", days, coins, description,
                "Сыграй матч в Dota 2 и выполни условие до его конца. " + AUTO_AFTER_MATCH, DOTA_REQ,
                q -> {
                    q.setDotaVerifyType(type);
                    q.setDotaTargetCount(target);
                });
    }

    // ── PUBG PC ─────────────────────────────────────────────────────────────────
    private List<Def> pubgPcPackA() {
        List<Def> l = new ArrayList<>();
        l.add(pubg("Сыграй 5 матчей", "5 матчей", PubgVerifyType.MATCHES_PLAYED, 5, null, 3, 1500));
        l.add(pubg("Выживи до Топ-20", "Топ-20", PubgVerifyType.TOP_N, 1, 20, 3, 1500));
        l.add(pubg("Выживи до Топ-5", "Топ-5", PubgVerifyType.TOP_N, 1, 5, 5, 3000));
        l.add(pubg("Сделай 5 убийств за один матч", "5 фрагов/матч", PubgVerifyType.KILLS, 1, 5, 3, 2200));
        l.add(pubg("Сделай 8 убийств за один матч", "8 фрагов/матч", PubgVerifyType.KILLS, 1, 8, 5, 3000));
        l.add(pubg("Нанеси 500 урона за один матч", "500 урона/матч", PubgVerifyType.DAMAGE, 1, 500, 3, 1500));
        l.add(pubg("Нанеси 1 500 урона за один матч", "1500 урона/матч", PubgVerifyType.DAMAGE, 1, 1500, 5, 3000));
        l.add(pubg("Финишируй в Топ-10 дважды за неделю", "Топ-10×2/неделя", PubgVerifyType.TOP_N, 2, 10, 7, 2200));
        return l;
    }

    private List<Def> pubgPcPackB() {
        List<Def> l = new ArrayList<>();
        l.add(pubg("Сыграй 8 матчей", "8 матчей", PubgVerifyType.MATCHES_PLAYED, 8, null, 5, 1800));
        l.add(pubg("Выживи до Топ-30 дважды", "Топ-30×2", PubgVerifyType.TOP_N, 2, 30, 5, 1800));
        l.add(pubg("Выживи до Топ-3", "Топ-3", PubgVerifyType.TOP_N, 1, 3, 7, 3000));
        l.add(pubg("Сделай 4 убийства за один матч", "4 фрага/матч", PubgVerifyType.KILLS, 1, 4, 3, 1800));
        l.add(pubg("Сделай 10 убийств за один матч", "10 фрагов/матч", PubgVerifyType.KILLS, 1, 10, 7, 3000));
        l.add(pubg("Нанеси 800 урона за один матч", "800 урона/матч", PubgVerifyType.DAMAGE, 1, 800, 3, 2000));
        l.add(pubg("Нанеси 2 000 урона за один матч", "2000 урона/матч", PubgVerifyType.DAMAGE, 1, 2000, 7, 3000));
        l.add(pubg("Финишируй в Топ-5 дважды за неделю", "Топ-5×2/неделя", PubgVerifyType.TOP_N, 2, 5, 7, 3000));
        return l;
    }

    private static Def pubg(String title, String label, PubgVerifyType type, int target, Integer threshold, int days, long coins) {
        String description = switch (type) {
            case MATCHES_PLAYED -> "Сыграй " + target + " завершённых матчей Battle Royale в PUBG PC с момента взятия квеста — прогресс считается автоматически.";
            case WINS -> "Выиграй матч Battle Royale в PUBG PC с момента взятия квеста — прогресс считается автоматически.";
            case TOP_N -> target > 1
                    ? "Доживи " + target + " раза до момента, когда в матче останется " + threshold + " или меньше игроков — прогресс считается автоматически."
                    : "Доживи до момента, когда в матче останется " + threshold + " или меньше игроков — прогресс считается автоматически.";
            case KILLS -> "Набери " + threshold + " и более убийств в одном матче Battle Royale — прогресс считается автоматически.";
            case DAMAGE -> "Суммарно нанеси " + fmt(threshold) + " и более единиц урона противникам за один матч — прогресс считается автоматически.";
        };
        return new Def(title, label, "PC", days, coins, description,
                "Играй матчи Battle Royale в любом режиме. " + AUTO, PUBG_REQ,
                q -> {
                    q.setPubgVerifyType(type);
                    q.setPubgTargetCount(target);
                    q.setPubgThreshold(threshold);
                });
    }

    // ── PUBG Mobile (по скриншоту) ──────────────────────────────────────────────
    private enum MobileKind { KILLS, DAMAGE, SURVIVE, ASSIST, RESCUE, REVIVE }

    private List<Def> pubgMobilePackA() {
        List<Def> l = new ArrayList<>();
        l.add(mobile("Уничтожь 3 врага за матч", "3 фрага/матч", MobileKind.KILLS, 3, 3, 1500));
        l.add(mobile("Уничтожь 8 врагов за матч", "8 фрагов/матч", MobileKind.KILLS, 8, 5, 2200));
        l.add(mobile("Нанеси 800 урона за матч", "800 урона/матч", MobileKind.DAMAGE, 800, 3, 1500));
        l.add(mobile("Нанеси 1,800 урона за матч", "1800 урона/матч", MobileKind.DAMAGE, 1800, 5, 2400));
        l.add(mobile("Продержись 10 минут за матч", "10 мин в матче", MobileKind.SURVIVE, 10, 3, 1500));
        l.add(mobile("Продержись 20 минут за матч", "20 мин в матче", MobileKind.SURVIVE, 20, 5, 1800));
        l.add(mobile("Помоги команде 5 раз за матч", "5 ассистов/матч", MobileKind.ASSIST, 5, 5, 2000));
        l.add(mobile("Спаси 2 союзника за матч", "2 спасения/матч", MobileKind.RESCUE, 2, 5, 2000));
        l.add(mobile("Сделай 2 возврата за матч", "2 возврата/матч", MobileKind.REVIVE, 2, 5, 1800));
        return l;
    }

    private List<Def> pubgMobilePackB() {
        List<Def> l = new ArrayList<>();
        l.add(mobile("Уничтожь 4 врага за матч", "4 фрага/матч", MobileKind.KILLS, 4, 3, 1800));
        l.add(mobile("Уничтожь 10 врагов за матч", "10 фрагов/матч", MobileKind.KILLS, 10, 5, 2600));
        l.add(mobile("Нанеси 1,000 урона за матч", "1000 урона/матч", MobileKind.DAMAGE, 1000, 3, 1800));
        l.add(mobile("Нанеси 3,000 урона за матч", "3000 урона/матч", MobileKind.DAMAGE, 3000, 5, 3000));
        l.add(mobile("Продержись 18 минут за матч", "18 мин в матче", MobileKind.SURVIVE, 18, 3, 1700));
        l.add(mobile("Продержись 30 минут за матч", "30 мин в матче", MobileKind.SURVIVE, 30, 5, 3000));
        l.add(mobile("Помоги команде 4 раза за матч", "4 ассиста/матч", MobileKind.ASSIST, 4, 3, 1800));
        l.add(mobile("Помоги команде 7 раз за матч", "7 ассистов/матч", MobileKind.ASSIST, 7, 5, 2600));
        l.add(mobile("Спаси 3 союзника за матч", "3 спасения/матч", MobileKind.RESCUE, 3, 5, 2600));
        return l;
    }

    private static Def mobile(String title, String label, MobileKind kind, int n, int days, long coins) {
        String column;
        String goal;
        String shown = kind == MobileKind.DAMAGE ? fmt(n) : String.valueOf(n);
        String mode = "Подходит любой режим.";
        switch (kind) {
            case KILLS -> { column = "Уничтожения"; goal = "уничтожь минимум " + n + " врагов за игру"; }
            case DAMAGE -> { column = "Урон"; goal = "нанеси минимум " + shown + " единиц урона за игру"; }
            case SURVIVE -> { column = "Время выживания"; goal = "выживай минимум " + n + " минут за игру"; }
            case ASSIST -> { column = "Помощь"; goal = "сделай минимум " + n + " ассистов за игру"; mode = "Подходит любой командный режим."; }
            case RESCUE -> { column = "Спасено"; goal = "подними минимум " + n + " союзников за игру"; mode = "Подходит любой командный режим."; }
            default -> { column = "Возвраты"; goal = "верни союзников в игру минимум " + n + " раз за один матч"; mode = "Подходит любой командный режим."; }
        }
        String value = shown + (kind == MobileKind.SURVIVE ? "+ мин" : "+");
        return new Def(title, label, "Mobile", days, coins,
                "Сыграй матч в PUBG Mobile и " + goal + ". " + mode,
                "1. Сыграй матч и выполни условие\n2. После матча открой экран результатов\n3. Сделай скриншот — должны быть видны: твой ник, колонка "
                        + column + " со значением " + value + ", дата матча\n4. Загрузи скриншот как отчёт",
                "Скриншот экрана результатов матча, где в колонке " + column + " стоит " + value
                        + " напротив твоего ника. Должны быть видны дата матча и ник игрока.",
                q -> { });
    }
}
