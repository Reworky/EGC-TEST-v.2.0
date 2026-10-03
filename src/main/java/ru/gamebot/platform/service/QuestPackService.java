package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.model.QuestPack;
import ru.gamebot.platform.domain.model.QuestPackSwitchLog;
import ru.gamebot.platform.domain.repository.QuestPackRepository;
import ru.gamebot.platform.domain.repository.QuestPackSwitchLogRepository;
import ru.gamebot.platform.domain.repository.QuestRepository;

/**
 * Пачки квестов по играм: в игре включена ровно одна пачка, остальные скрыты. Переключение вручную из админки.
 * Квесты, которые игрок уже взял, не трогаются — выключение пачки только закрывает ВЗЯТИЕ новых
 * (проверка active есть лишь в QuestService.takeQuestChecked), так что игрок довыполняет начатое.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestPackService {

    private static final String MAIN_PACK_NAME = "Основная";

    private final QuestPackRepository packRepository;
    private final QuestRepository questRepository;
    private final QuestPackSwitchLogRepository switchLogRepository;

    public record SwitchResult(int activated, int hidden) {}

    public List<QuestPack> packsOf(String gameName) {
        return packRepository.findAllByGameNameIgnoreCaseOrderByIdAsc(gameName);
    }

    public boolean hasPacks(String gameName) {
        return !packsOf(gameName).isEmpty();
    }

    public long questCount(QuestPack pack) {
        return questRepository.countByPackId(pack.getId());
    }

    public List<Quest> questsOf(QuestPack pack) {
        return questRepository.findAllByPackId(pack.getId());
    }

    public QuestPack get(Long packId) {
        return packRepository.findById(packId).orElseThrow(() -> new IllegalArgumentException("Пачка не найдена: " + packId));
    }

    /** Первое обращение к пачкам игры: создаёт «Основную» (включена) и кладёт в неё все сейчас активные квесты. */
    @Transactional
    public QuestPack ensureMainPack(String gameName) {
        var existing = packRepository.findFirstByGameNameIgnoreCaseAndSeederManagedTrue(gameName);
        if (existing.isPresent()) {
            return existing.get();
        }
        QuestPack main = new QuestPack();
        main.setGameName(gameName);
        main.setName(MAIN_PACK_NAME);
        main.setActive(packRepository.findFirstByGameNameIgnoreCaseAndActiveTrue(gameName).isEmpty());
        main.setSeederManaged(true);
        main.setCreatedAt(LocalDateTime.now());
        main = packRepository.save(main);
        for (Quest q : questRepository.findAllByGameNameIgnoreCase(gameName)) {
            if (q.isActive() && q.getPackId() == null && !q.isSponsored()) {
                q.setPackId(main.getId());
                questRepository.save(q);
            }
        }
        log.info("[QuestPack] Created main pack for '{}'", gameName);
        return main;
    }

    @Transactional
    public QuestPack createPack(String gameName) {
        ensureMainPack(gameName);
        int number = packsOf(gameName).size() + 1;
        QuestPack pack = new QuestPack();
        pack.setGameName(gameName);
        pack.setName("Пачка " + number);
        pack.setActive(false);
        pack.setSeederManaged(false);
        pack.setCreatedAt(LocalDateTime.now());
        return packRepository.save(pack);
    }

    /** Пачка игры с таким названием; если нет — создаётся выключенной (для сидеров, заводящих свои пачки). */
    @Transactional
    public QuestPack getOrCreatePack(String gameName, String name) {
        ensureMainPack(gameName);
        for (QuestPack p : packsOf(gameName)) {
            if (p.getName().equalsIgnoreCase(name)) {
                return p;
            }
        }
        QuestPack pack = new QuestPack();
        pack.setGameName(gameName);
        pack.setName(name);
        pack.setActive(false);
        pack.setSeederManaged(false);
        pack.setCreatedAt(LocalDateTime.now());
        return packRepository.save(pack);
    }

    @Transactional
    public QuestPack rename(Long packId, String name) {
        QuestPack pack = get(packId);
        pack.setName(name);
        return packRepository.save(pack);
    }

    /** Включает пачку, все остальные пачки игры выключаются. Квесты предыдущей пачки скрываются (с пометкой,
     *  чтобы вернуть их при обратном включении), квесты новой — возвращаются в работу. */
    @Transactional
    public SwitchResult switchTo(Long packId) {
        return switchTo(packId, QuestPackSwitchLog.MANUAL);
    }

    @Transactional
    public SwitchResult switchTo(Long packId, String source) {
        QuestPack target = get(packId);
        LocalDateTime now = LocalDateTime.now();
        String game = target.getGameName();
        QuestPack main = ensureMainPack(game);
        int activated = 0;
        int hidden = 0;
        for (Quest q : questRepository.findAllByGameNameIgnoreCase(game)) {
            if (q.isSponsored()) {
                continue;
            }
            if (q.getPackId() == null && q.isActive()) {
                q.setPackId(main.getId()); // активный квест вне пачек — в «Основную»
            }
            if (q.getPackId() == null) {
                continue; // устаревший выключенный квест — не воскрешаем
            }
            if (q.getPackId().equals(target.getId())) {
                q.setPackActivatedAt(now);
                if (!q.isActive() && (q.isPackSuspended() || !target.isSeederManaged())) {
                    q.setActive(true);
                    activated++;
                }
                q.setPackSuspended(false);
            } else if (q.isActive()) {
                q.setActive(false);
                q.setPackSuspended(true);
                hidden++;
            }
            questRepository.save(q);
        }
        for (QuestPack p : packsOf(game)) {
            boolean shouldBeActive = p.getId().equals(target.getId());
            if (p.isActive() != shouldBeActive) {
                p.setActive(shouldBeActive);
                packRepository.save(p);
            }
        }
        QuestPackSwitchLog entry = new QuestPackSwitchLog();
        entry.setGameName(game);
        entry.setPackName(target.getName());
        entry.setSwitchedAt(now);
        entry.setSource(source);
        entry.setActivated(activated);
        entry.setHidden(hidden);
        switchLogRepository.save(entry);
        log.info("[QuestPack] '{}': pack '{}' enabled (+{} / -{}, {})", game, target.getName(), activated, hidden, source);
        return new SwitchResult(activated, hidden);
    }

    /** Переносит квест в пачку (packId = null — вывести из пачек). Видимость подгоняется под статус пачки. */
    @Transactional
    public void assignQuest(Quest quest, Long packId) {
        quest.setPackId(packId);
        if (packId != null) {
            QuestPack pack = get(packId);
            if (!pack.isActive() && quest.isActive()) {
                quest.setActive(false);
                quest.setPackSuspended(true);
            } else if (pack.isActive() && !pack.isSeederManaged()) {
                quest.setActive(true);
                quest.setPackSuspended(false);
            }
        }
        questRepository.save(quest);
    }

    /**
     * Приводит квесты игры в соответствие с включённой пачкой. Вызывается на каждом старте ПОСЛЕ QuestSeeder
     * (тот на каждом старте заново включает/выключает квесты по своим спискам): квесты невключённых пачек снова
     * скрываем, квесты включённой не-«Основной» пачки возвращаем, свежесозданные сидером активные квесты
     * без пачки отправляем в «Основную».
     */
    @Transactional
    public void reconcile(String gameName) {
        QuestPack active = packRepository.findFirstByGameNameIgnoreCaseAndActiveTrue(gameName).orElse(null);
        QuestPack main = packRepository.findFirstByGameNameIgnoreCaseAndSeederManagedTrue(gameName).orElse(null);
        if (active == null) {
            if (main == null) {
                return;
            }
            main.setActive(true);
            active = packRepository.save(main);
        }
        for (Quest q : questRepository.findAllByGameNameIgnoreCase(gameName)) {
            if (q.isSponsored()) {
                continue;
            }
            boolean changed = false;
            if (q.getPackId() == null) {
                if (!q.isActive() || main == null) {
                    continue;
                }
                q.setPackId(main.getId());
                changed = true;
            }
            if (q.getPackId().equals(active.getId())) {
                if (!active.isSeederManaged() && !q.isActive()) {
                    q.setActive(true);
                    q.setPackSuspended(false);
                    changed = true;
                }
            } else if (q.isActive()) {
                q.setActive(false);
                q.setPackSuspended(true);
                changed = true;
            }
            if (changed) {
                questRepository.save(q);
            }
        }
    }

    public void reconcileAll() {
        for (String game : packRepository.findAllGameNames()) {
            try {
                reconcile(game);
            } catch (Exception e) {
                log.error("[QuestPack] reconcile failed for '{}'", game, e);
            }
        }
    }
}
