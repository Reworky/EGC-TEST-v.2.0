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
import ru.gamebot.platform.domain.enums.ClashRoyaleVerifyType;
import ru.gamebot.platform.domain.enums.ClashVerifyType;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.model.QuestPack;
import ru.gamebot.platform.domain.repository.QuestRepository;
import ru.gamebot.platform.service.QuestPackService;

/**
 * Пачки квестов Clash Royale и Clash of Clans («Сезон А» и «Сезон Б», согласовано 2026-10-03). Устройство, порядок
 * запуска и правила такие же, как у BrawlQuestPackSeeder: награда задаётся только при создании квеста, названия
 * должны быть уникальны среди ВСЕХ квестов игры, «Основная» пачка заводится первой (забирает текущие активные квесты).
 * Проверка у обеих игр — по разнице кумулятивных счётчиков профиля с момента взятия квеста (фильтров по режиму нет),
 * поэтому новизну между пачками дают другие числа и пороги. Пороги по ачивкам CoC (стены, Ратуши, золото) подобраны
 * ориентировочно — сверить по реальным профилям и при необходимости поправить цены в админке.
 */
@Slf4j
@Component
@Order(5)
@RequiredArgsConstructor
public class SupercellQuestPackSeeder implements CommandLineRunner {

    private static final String CR = "Clash Royale";
    private static final String COC = "Clash of Clans";
    private static final String AUTO = "Прогресс отслеживается автоматически, ничего сообщать не нужно.";

    private final QuestRepository questRepository;
    private final QuestPackService questPackService;

    private record Def(String title, String label, int days, long coins, String description, String instruction,
                       Consumer<Quest> verify) {}

    @Override
    @Transactional
    public void run(String... args) {
        try {
            questPackService.ensureMainPack(CR);
            seedPack(CR, "Сезон А", crPackA());
            seedPack(CR, "Сезон Б", crPackB());
        } catch (Exception e) {
            log.error("[SupercellQuestPackSeeder] Clash Royale failed", e);
        }
        try {
            questPackService.ensureMainPack(COC);
            seedPack(COC, "Сезон А", cocPackA());
            seedPack(COC, "Сезон Б", cocPackB());
        } catch (Exception e) {
            log.error("[SupercellQuestPackSeeder] Clash of Clans failed", e);
        }
    }

    private void seedPack(String game, String packName, List<Def> defs) {
        String requirements = "Ничего отправлять не нужно — прогресс проверяется автоматически через официальный API "
                + game + ", награда зачислится сама после выполнения условия.";
        QuestPack pack = questPackService.getOrCreatePack(game, packName);
        for (Def d : defs) {
            Quest q = questRepository.findFirstByTitleAndGameName(d.title(), game).orElse(null);
            if (q == null) {
                q = new Quest();
                q.setTitle(d.title());
                q.setGameName(game);
                q.setCategory(null);
                q.setPlatform("Mobile");
                q.setRewardXp(50);
                q.setRewardCoins(d.coins());
                q.setCouncilOnly(false);
                q.setCreatedAt(LocalDateTime.now());
                q.setActive(pack.isActive());
                q.setPackSuspended(!pack.isActive());
                log.info("[SupercellQuestPackSeeder] Created '{}' ({}) in pack '{}'", d.title(), game, packName);
            }
            q.setPackId(pack.getId());
            q.setDurationDays(d.days());
            q.setDurationText(d.days() + " дней");
            q.setDescription(d.description());
            q.setInstruction(d.instruction());
            q.setRequirements(requirements);
            q.setShortLabel(d.label());
            d.verify().accept(q);
            questRepository.save(q);
        }
    }

    // ── Clash Royale ────────────────────────────────────────────────────────────
    private List<Def> crPackA() {
        List<Def> l = new ArrayList<>();
        l.add(cr("Победи в 2 боях в Арене", "Победа×2", ClashRoyaleVerifyType.WINS, 2, 3, 1500));
        l.add(cr("Победи 8 раз в Арене", "Победа×8", ClashRoyaleVerifyType.WINS, 8, 5, 2800));
        l.add(cr("Набери 3 Короны в трёх боях", "3 Короны ×3", ClashRoyaleVerifyType.THREE_CROWN_WINS, 3, 5, 2200));
        l.add(cr("Набери 120 Кубков в Арене", "120 Кубков", ClashRoyaleVerifyType.TROPHIES, 120, 10, 2800));
        l.add(cr("Сыграй 3 боя", "Сыграй 3 боя", ClashRoyaleVerifyType.BATTLE_COUNT, 3, 3, 1300));
        l.add(cr("Сыграй 10 боёв", "Сыграй 10 боёв", ClashRoyaleVerifyType.BATTLE_COUNT, 10, 5, 2000));
        l.add(cr("Задонать 200 карт клану", "Донат 200 карт", ClashRoyaleVerifyType.DONATIONS, 200, 7, 3000));
        l.add(cr("Улучши карты на 5 уровней суммарно", "Карты +5 уровней", ClashRoyaleVerifyType.COLLECTION_LEVEL, 5, 10, 2200));
        l.add(cr("Победи 10 раз в Клановых войнах за сезон", "КВ: победа×10", ClashRoyaleVerifyType.WAR_DAY_WINS, 10, 14, 2800));
        return l;
    }

    private List<Def> crPackB() {
        List<Def> l = new ArrayList<>();
        l.add(cr("Победи в 4 боях в Арене", "Победа×4", ClashRoyaleVerifyType.WINS, 4, 4, 2200));
        l.add(cr("Победи 6 раз в Арене", "Победа×6", ClashRoyaleVerifyType.WINS, 6, 5, 2700));
        l.add(cr("Одержи победу с тремя Коронами", "3 Короны ×1", ClashRoyaleVerifyType.THREE_CROWN_WINS, 1, 3, 1200));
        l.add(cr("Одержи 4 победы с тремя Коронами", "3 Короны ×4", ClashRoyaleVerifyType.THREE_CROWN_WINS, 4, 7, 3000));
        l.add(cr("Набери 50 Кубков в Арене", "50 Кубков", ClashRoyaleVerifyType.TROPHIES, 50, 5, 1900));
        l.add(cr("Набери 200 Кубков в Арене", "200 Кубков", ClashRoyaleVerifyType.TROPHIES, 200, 14, 3800));
        l.add(cr("Сыграй 8 боёв", "Сыграй 8 боёв", ClashRoyaleVerifyType.BATTLE_COUNT, 8, 4, 1800));
        l.add(cr("Сыграй 15 боёв", "Сыграй 15 боёв", ClashRoyaleVerifyType.BATTLE_COUNT, 15, 7, 2400));
        l.add(cr("Задонать 50 карт клану", "Донат 50 карт", ClashRoyaleVerifyType.DONATIONS, 50, 5, 1500));
        l.add(cr("Улучши карты на 2 уровня суммарно", "Карты +2 уровня", ClashRoyaleVerifyType.COLLECTION_LEVEL, 2, 7, 1200));
        l.add(cr("Победи 5 раз в Клановых войнах за сезон", "КВ: победа×5", ClashRoyaleVerifyType.WAR_DAY_WINS, 5, 14, 2000));
        return l;
    }

    private static Def cr(String title, String label, ClashRoyaleVerifyType type, int target, int days, long coins) {
        String description = switch (type) {
            case WINS -> "Одержи " + target + " побед в боях Clash Royale с момента взятия квеста — прогресс считается автоматически.";
            case THREE_CROWN_WINS -> "Одержи " + target + " побед с тремя Коронами с момента взятия квеста — прогресс считается автоматически.";
            case TROPHIES -> "Набери суммарный прирост в " + target + " и более Кубков в рейтинге Арены с момента взятия квеста.";
            case BATTLE_COUNT -> "Проведи " + target + " боёв в Clash Royale с момента взятия квеста — победа не обязательна.";
            case DONATIONS -> "Передай клану " + target + " карт с момента взятия квеста — прогресс считается автоматически.";
            case COLLECTION_LEVEL -> "Суммарно повысь уровни своих карт на " + target + " с момента взятия квеста.";
            case WAR_DAY_WINS -> "Одержи " + target + " побед в боях Клановых войн за сезон — прогресс считается автоматически.";
            default -> title;
        };
        String instruction = switch (type) {
            case WINS -> "Играй рейтинговые бои и побеждай. ";
            case THREE_CROWN_WINS -> "Атакуй сразу обе боковые башни или давай быстрое давление, чтобы снести три башни. ";
            case TROPHIES -> "Побеждай в рейтинговых боях — каждая победа добавляет Кубки. ";
            case BATTLE_COUNT -> "Играй любые бои — победа не обязательна. ";
            case DONATIONS -> "Отдавай карты сокланам по их запросам в клановом чате. ";
            case COLLECTION_LEVEL -> "Улучшай карты за золото и копии карт: каждое улучшение даёт +1. ";
            case WAR_DAY_WINS -> "Участвуй в боевых днях Клановых войн и побеждай. ";
            default -> "";
        };
        return new Def(title, label, days, coins, description, instruction + AUTO, q -> {
            q.setClashRoyaleVerifyType(type);
            q.setClashRoyaleTargetCount(target);
        });
    }

    // ── Clash of Clans ──────────────────────────────────────────────────────────
    private List<Def> cocPackA() {
        List<Def> l = new ArrayList<>();
        l.add(coc("Выиграй 2 атаки в мультиплеере", "Победа×2: Мультиплеер", ClashVerifyType.ATTACK_WINS, 2, 3, 1800,
                "Выиграй 2 атаки в мультиплеере", "Атакуй в обычном мультиплеере и побеждай. "));
        l.add(coc("Выиграй 5 атак в мультиплеере", "Победа×5: Мультиплеер", ClashVerifyType.ATTACK_WINS, 5, 5, 3000,
                "Выиграй 5 атак в мультиплеере", "Атакуй в обычном мультиплеере и побеждай. "));
        l.add(coc("Набери 120 трофеев", "120 трофеев", ClashVerifyType.TROPHIES, 120, 5, 2000,
                "Набери прирост 120 трофеев", "Побеждай в атаках — каждая победа добавляет трофеи. "));
        l.add(coc("Набери 150 кубков в Строительной Базе", "150 кубков: Строительная", ClashVerifyType.BUILDER_TROPHIES, 150, 5, 2000,
                "Набери прирост 150 кубков в Строительной Базе", "Побеждай в боях Базы строителя. "));
        l.add(coc("Успешно защитись 5 раз", "Защита×5", ClashVerifyType.DEFENSE_WINS, 5, 5, 2000,
                "Отрази 5 атак на твою деревню", "Укрепляй оборону — защита засчитывается автоматически. "));
        l.add(coc("Собери 50 000 золота или эликсира за день", "50 000 ресурсов", ClashVerifyType.RESOURCES, 50000, 3, 2200,
                "Собери 50 000 золота или эликсира", "Собирай ресурсы из добытчиков и в атаках. "));
        l.add(coc("Задонать 150 войск клану", "Донат 150 войск", ClashVerifyType.DONATIONS, 150, 5, 1500,
                "Передай клану 150 войск", "Отдавай войска сокланам по их запросам. "));
        l.add(cocAch("Передай 5 заклинаний клану", "Донат 5 заклинаний", "Sharing is caring", 5, 5, 1500,
                "Передай клану 5 заклинаний", "Отдавай заклинания сокланам по их запросам в клановом чате. "));
        l.add(coc("Повысь уровень персонажа на 1", "Уровень +1", ClashVerifyType.EXP_LEVEL, 1, 7, 1600,
                "Повысь уровень своего профиля на 1", "Уровень растёт от опыта за атаки и улучшения. "));
        l.add(coc("Прокачай героев на 1 уровень", "Герои +1 уровень", ClashVerifyType.HERO_LEVELS, 1, 7, 1700,
                "Суммарно повысь уровни своих героев на 1", "Улучшай героев в Алтарях: каждое улучшение любого героя даёт +1. "));
        l.add(coc("Улучши войска и заклинания на 5 уровней", "Войска +5 уровней", ClashVerifyType.TROOP_LEVELS, 5, 10, 2200,
                "Суммарно повысь уровни войск и заклинаний на 5", "Улучшай войска и заклинания в Лаборатории: каждое улучшение даёт +1. "));
        l.add(cocAch("Разрушь 15 Ратуш в атаках мультиплеера", "15 Ратуш разрушить", "Humiliator", 15, 5, 2000,
                "Разрушь 15 Ратуш противников в атаках мультиплеера", "Атакуй в обычном мультиплеере и целься в Ратуши. "));
        l.add(cocAch("Разрушь 200 стен в атаках мультиплеера", "200 стен разрушить", "Wall Buster", 200, 5, 1800,
                "Разрушь 200 стен противников в атаках мультиплеера", "Стены ломают гиганты, стенобои и заклинание землетрясения. "));
        l.add(cocAch("Награбь 1 000 000 золота в атаках", "1 млн золота награбить", "Gold Grab", 1000000, 7, 2200,
                "Награбь 1 000 000 золота в атаках", "Атакуй базы с полными хранилищами золота. "));
        l.add(coc("Заработай 5 звёзд в Клановых войнах", "КВ: 5 звёзд", ClashVerifyType.WAR_STARS, 5, 14, 2200,
                "Заработай 5 звёзд в Клановых войнах", "Участвуй в клановой войне и атакуй с максимальным числом звёзд. "));
        return l;
    }

    private List<Def> cocPackB() {
        List<Def> l = new ArrayList<>();
        l.add(coc("Выиграй 4 атаки в мультиплеере", "Победа×4: Мультиплеер", ClashVerifyType.ATTACK_WINS, 4, 5, 2600,
                "Выиграй 4 атаки в мультиплеере", "Атакуй в обычном мультиплеере и побеждай. "));
        l.add(coc("Выиграй 7 атак в мультиплеере", "Победа×7: Мультиплеер", ClashVerifyType.ATTACK_WINS, 7, 7, 3400,
                "Выиграй 7 атак в мультиплеере", "Атакуй в обычном мультиплеере и побеждай. "));
        l.add(coc("Набери 100 трофеев", "100 трофеев", ClashVerifyType.TROPHIES, 100, 5, 1800,
                "Набери прирост 100 трофеев", "Побеждай в атаках — каждая победа добавляет трофеи. "));
        l.add(coc("Набери 60 кубков в Строительной Базе", "60 кубков: Строительная", ClashVerifyType.BUILDER_TROPHIES, 60, 3, 1300,
                "Набери прирост 60 кубков в Строительной Базе", "Побеждай в боях Базы строителя. "));
        l.add(coc("Успешно защитись 2 раза", "Защита×2", ClashVerifyType.DEFENSE_WINS, 2, 3, 1300,
                "Отрази 2 атаки на твою деревню", "Укрепляй оборону — защита засчитывается автоматически. "));
        l.add(coc("Успешно защитись 8 раз", "Защита×8", ClashVerifyType.DEFENSE_WINS, 8, 7, 2800,
                "Отрази 8 атак на твою деревню", "Укрепляй оборону — защита засчитывается автоматически. "));
        l.add(coc("Собери 40 000 золота или эликсира за день", "40 000 ресурсов", ClashVerifyType.RESOURCES, 40000, 3, 1900,
                "Собери 40 000 золота или эликсира", "Собирай ресурсы из добытчиков и в атаках. "));
        l.add(coc("Задонать 500 войск клану", "Донат 500 войск", ClashVerifyType.DONATIONS, 500, 7, 3000,
                "Передай клану 500 войск", "Отдавай войска сокланам по их запросам. "));
        l.add(coc("Задонать 100 войск клану", "Донат 100 войск", ClashVerifyType.DONATIONS, 100, 4, 1200,
                "Передай клану 100 войск", "Отдавай войска сокланам по их запросам. "));
        l.add(coc("Прокачай героев на 3 уровня", "Герои +3 уровня", ClashVerifyType.HERO_LEVELS, 3, 10, 3500,
                "Суммарно повысь уровни своих героев на 3", "Улучшай героев в Алтарях: каждое улучшение любого героя даёт +1. "));
        l.add(coc("Улучши войска и заклинания на 2 уровня", "Войска +2 уровня", ClashVerifyType.TROOP_LEVELS, 2, 7, 1200,
                "Суммарно повысь уровни войск и заклинаний на 2", "Улучшай войска и заклинания в Лаборатории: каждое улучшение даёт +1. "));
        l.add(cocAch("Разрушь 5 Ратуш в атаках мультиплеера", "5 Ратуш разрушить", "Humiliator", 5, 3, 1200,
                "Разрушь 5 Ратуш противников в атаках мультиплеера", "Атакуй в обычном мультиплеере и целься в Ратуши. "));
        l.add(cocAch("Разрушь 300 стен в атаках мультиплеера", "300 стен разрушить", "Wall Buster", 300, 7, 2400,
                "Разрушь 300 стен противников в атаках мультиплеера", "Стены ломают гиганты, стенобои и заклинание землетрясения. "));
        l.add(cocAch("Убери 40 препятствий", "40 препятствий убрать", "Nice and Tidy", 40, 5, 1800,
                "Убери 40 препятствий в своей деревне", "Расчищай деревню от деревьев, камней и грибов — каждое убранное препятствие засчитывается. "));
        l.add(cocAch("Награбь 3 000 000 золота в атаках", "3 млн золота награбить", "Gold Grab", 3000000, 10, 3000,
                "Награбь 3 000 000 золота в атаках", "Атакуй базы с полными хранилищами золота. "));
        return l;
    }

    private static Def coc(String title, String label, ClashVerifyType type, int target, int days, long coins,
                           String description, String instruction) {
        return new Def(title, label, days, coins, description + " с момента взятия квеста — прогресс считается автоматически.",
                instruction + AUTO, q -> {
                    q.setClashVerifyType(type);
                    q.setClashTargetCount(target);
                    q.setClashAchievementName(null);
                });
    }

    private static Def cocAch(String title, String label, String achievement, int target, int days, long coins,
                              String description, String instruction) {
        return new Def(title, label, days, coins, description + " с момента взятия квеста — прогресс считается автоматически.",
                instruction + AUTO, q -> {
                    q.setClashVerifyType(ClashVerifyType.ACHIEVEMENT);
                    q.setClashTargetCount(target);
                    q.setClashAchievementName(achievement);
                });
    }
}
