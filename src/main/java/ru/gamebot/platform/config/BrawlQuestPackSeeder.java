package ru.gamebot.platform.config;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.enums.BrawlVerifyType;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.model.QuestPack;
import ru.gamebot.platform.domain.repository.QuestRepository;
import ru.gamebot.platform.service.QuestPackService;

/**
 * Пачки квестов Brawl Stars №2 «Сезон А» и №3 «Сезон Б» (согласовано 2026-10-03): два полных каталога, такие же
 * разноплановые, как текущий («Основная»), но с другими условиями, числами и бойцами. Пачка включается в админке
 * (Игровые квесты → Brawl Stars → 📦 Пачки) — одна на игру, остальные скрыты.
 *
 * Идемпотентен, вызывается на каждом старте ПОСЛЕ QuestSeeder (Order 2) и ДО QuestPackRunner. Награда задаётся
 * только при создании квеста — ручные правки цен из админки не затираются. Названия должны быть уникальны
 * среди ВСЕХ квестов Brawl Stars (QuestSeeder ищет квесты по title+game).
 * Сначала ensureMainPack: «Основная» забирает все сейчас активные квесты, иначе новые попали бы в неё.
 */
@Slf4j
@Component
@Order(5)
@RequiredArgsConstructor
public class BrawlQuestPackSeeder implements CommandLineRunner {

    private static final String GAME = "Brawl Stars";
    private static final String AUTO_REQ = "Ничего отправлять не нужно — прогресс проверяется автоматически через официальный API "
            + "Brawl Stars, награда зачислится сама после выполнения условия.";
    private static final String AUTO = "Прогресс отслеживается автоматически, ничего сообщать не нужно.";

    private final QuestRepository questRepository;
    private final QuestPackService questPackService;

    private record Def(String title, String shortLabel, BrawlVerifyType type, int target,
                       boolean victory, boolean ranked, boolean team, String brawlers,
                       int days, long coins, String description, String instruction) {}

    @Override
    @Transactional
    public void run(String... args) {
        try {
            questPackService.ensureMainPack(GAME);
            seedPack("Сезон А", packA());
            seedPack("Сезон Б", packB());
        } catch (Exception e) {
            log.error("[BrawlQuestPackSeeder] failed", e);
        }
    }

    private void seedPack(String packName, List<Def> defs) {
        QuestPack pack = questPackService.getOrCreatePack(GAME, packName);
        for (Def d : defs) {
            Quest q = questRepository.findFirstByTitleAndGameName(d.title(), GAME).orElse(null);
            if (q == null) {
                q = new Quest();
                q.setTitle(d.title());
                q.setGameName(GAME);
                q.setCategory(null);
                q.setPlatform("Mobile");
                q.setRewardXp(50);
                q.setRewardCoins(d.coins());
                q.setCouncilOnly(false);
                q.setCreatedAt(LocalDateTime.now());
                q.setActive(pack.isActive());
                q.setPackSuspended(!pack.isActive());
                log.info("[BrawlQuestPackSeeder] Created '{}' in pack '{}'", d.title(), packName);
            }
            q.setPackId(pack.getId());
            q.setDurationDays(d.days());
            q.setDurationText(d.days() + " дней");
            q.setDescription(d.description());
            q.setInstruction(d.instruction());
            q.setRequirements(AUTO_REQ);
            q.setShortLabel(d.shortLabel());
            q.setBrawlVerifyType(d.type());
            q.setBrawlTargetCount(d.target());
            q.setBrawlRequireVictory(d.victory());
            q.setBrawlRequireRanked(d.ranked());
            q.setBrawlRequireTeam(d.team());
            q.setBrawlModeKeys(null);
            q.setBrawlBrawlerNames(d.brawlers());
            questRepository.save(q);
        }
    }

    // ── Пачка 2 «Сезон А» ───────────────────────────────────────────────────────
    private List<Def> packA() {
        List<Def> l = new ArrayList<>();
        l.add(play("Сразись в бою 10 раз", "Сразись 10 раз", 10, 1500));
        l.add(win("Выиграй бой 7 раз", "Победа×7", 7, 1700));
        l.add(win("Выиграй бой 12 раз", "Победа×12", 12, 2200));
        l.add(trophies("Набери 100 трофеев", "100 трофеев", 100, 2200));
        l.add(ranked("Выиграй бой 3 раза в ранговом режиме", "Ранговый ×3", 3, true, 1900));
        l.add(ranked("Выиграй бой 8 раз в ранговом режиме", "Ранговый ×8", 8, true, 3200));
        l.add(team("Сыграй 8 матчей в команде", "8 матчей в команде", 8, false, 1800));
        l.add(team("Выиграй бой 4 раза в команде", "Победа×4 в команде", 4, true, 2000));
        l.add(friend("Сыграй 5 боёв в команде с другом", "5 боёв с другом", 5, 2400));
        l.add(heroWin("Кольт, Булл или Джесси", "COLT,BULL,JESSIE", 5, 2400));
        l.add(heroWin("Брок, Поко или Пайпер", "BROCK,POCO,PIPER", 5, 2400));
        l.add(heroWin("Роза, Биби или Динамайк", "ROSA,BIBI,DYNAMIKE", 5, 2400));
        l.add(heroWin("Барли, Рико или Мортис", "BARLEY,RICO,MORTIS", 8, 3000));
        l.add(heroWin("Кольт, Брок или Булл", "COLT,BROCK,BULL", 10, 3000));
        l.add(heroPlay("Сыграй 10 боёв с бойцом Поко, Роза или Джесси", "10 боёв: Поко/Роза/Джесси", "POCO,ROSA,JESSIE", 10, 1600));
        l.add(newBrawler("Пополни коллекцию: получи нового бойца", "Новый боец", 4000));
        l.add(power("Прокачай силу бойцов на 5 уровней", "Сила бойцов +5", 5, 1800));
        l.add(rank("Подними ранг бойцов на 3", "Ранг бойцов +3", 3, 1800));
        l.add(unlock("Открой 2 улучшения бойца", "2 улучшения бойца", 2, 2200));
        l.add(level("Повысь уровень профиля на 2", "Уровень профиля +2", 2, 1600));
        return l;
    }

    // ── Пачка 3 «Сезон Б» ───────────────────────────────────────────────────────
    private List<Def> packB() {
        List<Def> l = new ArrayList<>();
        l.add(play("Сразись в бою 6 раз", "Сразись 6 раз", 6, 1300));
        l.add(win("Выиграй бой 10 раз", "Победа×10", 10, 2000));
        l.add(win("Выиграй бой 15 раз", "Победа×15", 15, 2600));
        l.add(trophies("Набери 60 трофеев", "60 трофеев", 60, 1800));
        l.add(trophies("Набери 150 трофеев", "150 трофеев", 150, 3000));
        l.add(ranked("Сыграй 6 боёв в ранговом режиме", "Ранговый: 6 боёв", 6, false, 1800));
        l.add(ranked("Выиграй бой 6 раз в ранговом режиме", "Ранговый ×6", 6, true, 2800));
        l.add(team("Сыграй 12 матчей в команде", "12 матчей в команде", 12, false, 2200));
        l.add(team("Выиграй бой 6 раз в команде", "Победа×6 в команде", 6, true, 2400));
        l.add(friend("Сыграй 2 боя в команде с другом", "2 боя с другом", 2, 2000));
        l.add(heroWin("Бо, Тара или Карл", "BO,TARA,CARL", 5, 2400));
        l.add(heroWin("Пенни, Эмз или Гейл", "PENNY,EMZ,GALE", 5, 2400));
        l.add(heroWin("Макс, Базз или Тик", "MAX,BUZZ,TICK", 5, 2400));
        l.add(heroWin("Бо, Пенни или Макс", "BO,PENNY,MAX", 8, 3000));
        l.add(heroWin("Тара, Эмз или Базз", "TARA,EMZ,BUZZ", 15, 3000));
        l.add(heroPlay("Сыграй 8 боёв с бойцом Карл, Гейл или Тик", "8 боёв: Карл/Гейл/Тик", "CARL,GALE,TICK", 8, 1600));
        l.add(power("Прокачай силу бойцов на 2 уровня", "Сила бойцов +2", 2, 1400));
        l.add(rank("Подними ранг бойцов на 1", "Ранг бойцов +1", 1, 1300));
        l.add(unlock("Открой 3 улучшения бойца", "3 улучшения бойца", 3, 2800));
        l.add(level("Повысь уровень профиля на 3", "Уровень профиля +3", 3, 2000));
        return l;
    }

    // ── Построители квестов ─────────────────────────────────────────────────────
    private static Def play(String title, String label, int n, long coins) {
        return new Def(title, label, BrawlVerifyType.BATTLES, n, false, false, false, null, 5, coins,
                "Проведи " + n + " боёв в любом режиме Brawl Stars — победа не обязательна.",
                "Играй в любом режиме — победа не обязательна. " + AUTO);
    }

    private static Def win(String title, String label, int n, long coins) {
        return new Def(title, label, BrawlVerifyType.BATTLES, n, true, false, false, null, n >= 10 ? 7 : 5, coins,
                "Победи в " + n + " боях Brawl Stars — режим любой.",
                "Играй в любом режиме и побеждай — победы суммируются из разных сессий. " + AUTO);
    }

    private static Def trophies(String title, String label, int n, long coins) {
        return new Def(title, label, BrawlVerifyType.TROPHIES, n, false, false, false, null, 5, coins,
                "Набери суммарный прирост в " + n + " и более трофеев в Brawl Stars — тип боя не важен.",
                "Играй в любых боях, где начисляются трофеи, — каждая победа их прибавляет. " + AUTO);
    }

    private static Def ranked(String title, String label, int n, boolean victory, long coins) {
        return new Def(title, label, BrawlVerifyType.BATTLES, n, victory, true, false, null, 7, coins,
                victory ? "Победи в " + n + " боях ранговых матчей Brawl Stars."
                        : "Проведи " + n + " боёв в ранговом режиме Brawl Stars — победа не обязательна.",
                "Заходи в ранговый режим" + (victory ? " и побеждай. Победы суммируются из разных сессий. " : ". ") + AUTO);
    }

    private static Def team(String title, String label, int n, boolean victory, long coins) {
        return new Def(title, label, BrawlVerifyType.BATTLES, n, victory, false, true, null, 7, coins,
                victory ? "Победи в " + n + " боях, играя в составе команды с другими игроками."
                        : "Проведи " + n + " боёв, играя в составе команды с другими игроками.",
                "Зайди в бой через режим команды" + (victory ? " и побеждай. " : " — победа не обязательна. ") + AUTO);
    }

    private static Def friend(String title, String label, int n, long coins) {
        return new Def(title, label, BrawlVerifyType.PARTNER_BATTLES, n, false, false, true, null, 7, coins,
                "Проведи " + n + (n % 10 >= 2 && n % 10 <= 4 && (n < 10 || n > 20) ? " боя" : " боёв") + " в команде вместе со своим рефералом или участником отряда — победа не обязательна.",
                "При взятии квеста выбери партнёра из списка (реферал или отрядник с привязанным тегом Brawl Stars). "
                        + "Зайдите в бой вместе через режим команды. " + AUTO);
    }

    private static Def heroWin(String namesRu, String api, int n, long coins) {
        String title = "Выиграй бой " + n + " " + (n % 10 >= 2 && n % 10 <= 4 && (n < 10 || n > 20) ? "раза" : "раз")
                + " с бойцом " + namesRu;
        return new Def(title, "Победа×" + n + ": " + namesRu.replace(", ", "/").replace(" или ", "/"),
                BrawlVerifyType.BATTLES, n, true, false, false, api, 7, coins,
                "Победи " + n + " раз, играя за " + namesRu + " — в любом режиме.",
                "Выбери одного из трёх бойцов. Победы с разными бойцами суммируются. " + AUTO);
    }

    private static Def heroPlay(String title, String label, String api, int n, long coins) {
        return new Def(title, label, BrawlVerifyType.BATTLES, n, false, false, false, api, 5, coins,
                "Проведи " + n + " боёв за одного из указанных бойцов — победа не обязательна, режим любой.",
                "Выбери одного из трёх бойцов — бои с разными бойцами суммируются. Победа не обязательна. " + AUTO);
    }

    private static Def newBrawler(String title, String label, long coins) {
        return new Def(title, label, BrawlVerifyType.NEW_BRAWLER, 1, false, false, false, null, 7, coins,
                "Получи любого нового бойца в коллекцию — способ получения не важен.",
                "Получай бойцов на Trophy Road, в дропах или в магазине — засчитывается новый боец, появившийся после взятия квеста. " + AUTO);
    }

    private static Def power(String title, String label, int n, long coins) {
        return new Def(title, label, BrawlVerifyType.BRAWLER_POWER, n, false, false, false, null, 7, coins,
                "Суммарно повысь уровень силы своих бойцов на " + n + " с момента взятия квеста — прогресс считается автоматически.",
                "Улучшай бойцов за монеты и очки силы: каждое повышение силы любого бойца даёт +1. " + AUTO);
    }

    private static Def rank(String title, String label, int n, long coins) {
        return new Def(title, label, BrawlVerifyType.BRAWLER_RANK, n, false, false, false, null, 7, coins,
                "Суммарно повысь ранг своих бойцов на " + n + " с момента взятия квеста — прогресс считается автоматически.",
                "Играй бойцами и набирай трофеи: ранг растёт, когда боец достигает нового порога трофеев. Считается сумма по всем бойцам. " + AUTO);
    }

    private static Def unlock(String title, String label, int n, long coins) {
        return new Def(title, label, BrawlVerifyType.UNLOCKS, n, false, false, false, null, 10, coins,
                "Открой " + n + " улучшения бойцов: гаджеты, звёздные силы, снаряжение или гиперзаряды — засчитываются новые, открытые после взятия квеста.",
                "Прокачивай бойцов до нужного уровня силы и открывай их улучшения в магазине или за награды. " + AUTO);
    }

    private static Def level(String title, String label, int n, long coins) {
        return new Def(title, label, BrawlVerifyType.EXP_LEVEL, n, false, false, false, null, 7, coins,
                "Повысь уровень своего профиля Brawl Stars на " + n + " с момента взятия квеста — прогресс считается автоматически.",
                "Уровень профиля растёт от опыта за бои и выполненные задания в игре. " + AUTO);
    }
}
