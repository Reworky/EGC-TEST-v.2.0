package ru.gamebot.platform.domain.repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.gamebot.platform.domain.model.AppUser;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByTelegramId(Long telegramId);

    Optional<AppUser> findByNicknameIgnoreCase(String nickname);

    /** Уникальный индекс на nickname регистрозависимый, поэтому «Саша» и «саша» могут жить в базе одновременно —
     * findByNicknameIgnoreCase на таком нике бросает IncorrectResultSizeDataAccessException («2 results»). */
    Optional<AppUser> findFirstByNicknameIgnoreCaseOrderByIdAsc(String nickname);

    boolean existsByNicknameIgnoreCase(String nickname);

    Optional<AppUser> findByTelegramUsernameIgnoreCase(String telegramUsername);

    long countByLastBotActivityAtAfter(LocalDateTime since);

    long countByLastMiniAppOpenAtAfter(LocalDateTime since);

    /** Уникальные активные игроки за период. Намеренно смотрит на 3 поля, не только на lastBotActivityAt/
     *  lastMiniAppOpenAt: те завели только 2026-09-08 ("замер активности бот vs мини-апп"), поэтому для
     *  30-дневного окна их одних не хватает — первые ~24 дня этого окна для них попросту NULL, что молча
     *  занижает счётчик (реальный инцидент: MAU по этим двум полям 160 vs 911 по lastActivityDate на ту же
     *  дату). lastActivityDate ведётся с самого начала проекта — добавляем его как OR, чтобы не терять
     *  историю, накопленную до 09-08. Основа для DAU/MAU (см. UserService.getEngagementReport). */
    @Query("SELECT COUNT(DISTINCT u) FROM AppUser u WHERE u.lastActivityDate >= :sinceDate OR u.lastBotActivityAt >= :sinceDateTime OR u.lastMiniAppOpenAt >= :sinceDateTime")
    long countDistinctActiveSince(@Param("sinceDate") java.time.LocalDate sinceDate, @Param("sinceDateTime") LocalDateTime sinceDateTime);

    /** То же самое, с фильтром по источнику трафика (см. UserService.getEngagementReport) — sourceFilter:
     *  null = вся аудитория, "ORGANIC" = только trafficSourceCode IS NULL (органика/реферал), иначе —
     *  точное совпадение с кодом конкретной рекламной закупки. maxCreatedAt (не null только для
     *  "Без рекламы") — дополнительно отсекает свежие регистрации: сразу после закупа в MAU/DAU
     *  попадает партия только что зарегистрированных, которые технически "активны" просто потому что
     *  недавно зашли, ещё не успели ни прижиться, ни отвалиться — это раздувает цифру до того, как
     *  станет ясно, реальные это игроки или нет. null = без этого ограничения. */
    @Query("SELECT COUNT(DISTINCT u) FROM AppUser u WHERE (u.lastActivityDate >= :sinceDate OR u.lastBotActivityAt >= :sinceDateTime OR u.lastMiniAppOpenAt >= :sinceDateTime) "
            + "AND (:sourceFilter IS NULL OR (:sourceFilter = 'ORGANIC' AND u.trafficSourceCode IS NULL) OR u.trafficSourceCode = :sourceFilter) "
            + "AND (:maxCreatedAt IS NULL OR u.createdAt <= :maxCreatedAt)")
    long countDistinctActiveSince(@Param("sinceDate") java.time.LocalDate sinceDate, @Param("sinceDateTime") LocalDateTime sinceDateTime,
                                   @Param("sourceFilter") String sourceFilter, @Param("maxCreatedAt") LocalDateTime maxCreatedAt);

    /** То же самое, с сегментацией по членству в отряде вместо источника трафика - разовая диагностика
     *  гипотезы "отряды удерживают активность" (ТЗ EGC_TZ_otryady, 2026-10-01), Этап 1. inSquad=true -
     *  squadId IS NOT NULL, false - IS NULL. Отдельный метод, а не переиспользование sourceFilter: это
     *  два разных среза аудитории, смешивать их в одном параметре было бы путаницей осей. */
    @Query("SELECT COUNT(DISTINCT u) FROM AppUser u WHERE (u.lastActivityDate >= :sinceDate OR u.lastBotActivityAt >= :sinceDateTime OR u.lastMiniAppOpenAt >= :sinceDateTime) "
            + "AND ((:inSquad = true AND u.squadId IS NOT NULL) OR (:inSquad = false AND u.squadId IS NULL))")
    long countDistinctActiveSinceBySquad(@Param("sinceDate") java.time.LocalDate sinceDate, @Param("sinceDateTime") LocalDateTime sinceDateTime,
                                          @Param("inSquad") boolean inSquad);

    /**
     * Блокирует строку пользователя на время транзакции (SELECT ... FOR UPDATE).
     * Нужно везде, где идёт схема "проверить лимит → записать" (взятие квеста и т.п.),
     * чтобы конкурентные запросы не могли пройти проверку одновременно (гонка состояний).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM AppUser u WHERE u.id = :id")
    Optional<AppUser> findByIdForUpdate(@Param("id") Long id);

    /** Отметка отправленного напоминания (NotificationGateService) - точечный UPDATE, чтобы не затирать остальные поля игрока. */
    @Modifying
    @Query("UPDATE AppUser u SET u.lastNudgeAt = :at, u.lastNudgePriority = :priority, u.lastNudgeReturned = false WHERE u.id = :id")
    int markNudge(@Param("id") Long id, @Param("at") LocalDateTime at, @Param("priority") int priority);

    @Modifying
    @Query("UPDATE AppUser u SET u.lastNudgeReturned = true WHERE u.id = :id")
    int markNudgeReturned(@Param("id") Long id);

    List<AppUser> findAllByRegistrationCompletedTrueOrderByXpDescTelegramIdAsc();

    List<AppUser> findAllByRegistrationCompletedTrueOrderByWeeklyXpDescTelegramIdAsc();

    List<AppUser> findTop20ByRegistrationCompletedTrueOrderByXpDescTelegramIdAsc();

    /** Ввёл никнейм, но не подтвердил подписку на канал — кандидаты на автоактивацию,
     * если бот сам обнаружит, что они уже подписались (не дождавшись возврата в бота). */
    List<AppUser> findAllByProfileCompletedTrueAndRegistrationCompletedFalse();

    /** Воронка ДО того, как игрок попадает в когорту churnDayReport (та смотрит только на
     *  registrationCompleted=true) - сколько вообще когда-либо написали боту (AppUser создаётся в
     *  UserService.getOrCreate на первое сообщение), сколько дошли до ввода ника (profileCompleted),
     *  и сколько застряли именно на обязательной подписке на канал (profileCompleted=true,
     *  registrationCompleted=false - см. UserService.findPendingChannelActivation,
     *  GamePlatformBot.checkPendingChannelActivations). Карта роста EGC, п.6 - без этого слоя кризис
     *  активации дня 0 был виден только частично, учитывая людей, уже прошедших эту подписку. */
    long countByCreatedAtLessThanEqual(LocalDateTime cutoff);

    long countByProfileCompletedTrueAndCreatedAtLessThanEqual(LocalDateTime cutoff);

    long countByProfileCompletedTrueAndRegistrationCompletedFalseAndCreatedAtLessThanEqual(LocalDateTime cutoff);

    List<AppUser> findTop20ByRegistrationCompletedTrueOrderByWeeklyXpDescTelegramIdAsc();

    /** Только реально активные на этой неделе игроки — без этого топ-20 добивался нулями (неактивные, но с малым TG ID). */
    List<AppUser> findTop20ByRegistrationCompletedTrueAndWeeklyXpGreaterThanOrderByWeeklyXpDescTelegramIdAsc(long weeklyXp);

    long countByRegistrationCompletedTrue();

    @Query("SELECT COALESCE(SUM(u.coins), 0) FROM AppUser u")
    long sumAllCoins();

    @Query("SELECT COALESCE(SUM(u.tickets), 0) FROM AppUser u")
    long sumAllTickets();

    @Query("SELECT COUNT(u) FROM AppUser u WHERE u.registrationCompleted = true AND u.createdAt >= :since")
    long countNewUsersSince(@Param("since") LocalDateTime since);

    List<AppUser> findTop5ByRegistrationCompletedTrueOrderByXpDescTelegramIdAsc();

    List<AppUser> findAllByFraudSuspectTrue();

    List<AppUser> findAllByAvatarFrameColorAndAvatarFrameImageIsNull(String avatarFrameColor);

    List<AppUser> findAllByTrafficSourceCodeOrderByCreatedAtDesc(String trafficSourceCode);

    long countByTrafficSourceCode(String trafficSourceCode);

    long countByTrafficSourceCodeAndProfileCompletedTrue(String trafficSourceCode);

    long countByTrafficSourceCodeAndRegistrationCompletedTrue(String trafficSourceCode);

    long countByTrafficSourceCodeAndWelcomeBonusPaidTrue(String trafficSourceCode);

    @Query("SELECT COUNT(u) FROM AppUser u WHERE u.registrationCompleted = true AND u.lastActivityDate = :today")
    long countActiveOnDate(@Param("today") java.time.LocalDate today);

    @Query("SELECT COUNT(u) FROM AppUser u WHERE u.registrationCompleted = true AND u.lastActivityDate >= :since")
    long countActiveSince(@Param("since") java.time.LocalDate since);

    /** То же самое ("Активны за N дней", вкладка «Активность»), сегментировано по членству в отряде -
     *  ТЗ EGC_TZ_otryady (2026-10-01), Этап 1. */
    @Query("SELECT COUNT(u) FROM AppUser u WHERE u.registrationCompleted = true AND u.lastActivityDate >= :since "
            + "AND ((:inSquad = true AND u.squadId IS NOT NULL) OR (:inSquad = false AND u.squadId IS NULL))")
    long countActiveSinceBySquad(@Param("since") java.time.LocalDate since, @Param("inSquad") boolean inSquad);

    @Query("SELECT COUNT(u) FROM AppUser u WHERE u.registrationCompleted = true AND u.weeklyXp > :weeklyXp")
    long countWithMoreWeeklyXp(@Param("weeklyXp") long weeklyXp);

    @Query("SELECT COUNT(u) FROM AppUser u WHERE u.registrationCompleted = true AND u.weeklyXp > 0")
    long countActiveThisWeek();

    @Query("SELECT COUNT(u) FROM AppUser u WHERE u.registrationCompleted = true AND u.createdAt >= :from AND u.createdAt < :to")
    long countRegisteredBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(u) FROM AppUser u WHERE u.registrationCompleted = true AND u.createdAt >= :from AND u.createdAt < :to AND u.lastActivityDate >= :activeSince")
    long countRegisteredBetweenAndActiveSince(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to, @Param("activeSince") java.time.LocalDate activeSince);

    /** Та же когорта "возврата" (UserService.retention), сегментированная по ТЕКУЩЕМУ членству в отряде -
     *  см. countDistinctActiveSinceBySquad выше про смысл inSquad и почему не переиспользуется sourceFilter. */
    @Query("SELECT COUNT(u) FROM AppUser u WHERE u.registrationCompleted = true AND u.createdAt >= :from AND u.createdAt < :to "
            + "AND ((:inSquad = true AND u.squadId IS NOT NULL) OR (:inSquad = false AND u.squadId IS NULL))")
    long countRegisteredBetweenBySquad(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to, @Param("inSquad") boolean inSquad);

    @Query("SELECT COUNT(u) FROM AppUser u WHERE u.registrationCompleted = true AND u.createdAt >= :from AND u.createdAt < :to AND u.lastActivityDate >= :activeSince "
            + "AND ((:inSquad = true AND u.squadId IS NOT NULL) OR (:inSquad = false AND u.squadId IS NULL))")
    long countRegisteredBetweenAndActiveSinceBySquad(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                                      @Param("activeSince") java.time.LocalDate activeSince, @Param("inSquad") boolean inSquad);

    long countByRegistrationCompletedTrueAndSquadIdIsNotNull();

    long countByRegistrationCompletedTrueAndSquadIdIsNull();

    /** Сырьё для «момента оттока» (карта роста EGC, п.2): [createdAt, lastActivityDate] каждого
     *  зарегистрированного игрока, чей аккаунт создан не позже maxCreatedAt - чтобы в когорту не попадали
     *  совсем свежие регистрации, которые технически ещё не успели показать свой реальный день отвала
     *  (см. UserService.churnDayReport). lastActivityDate обновляется на КАЖДОЕ сообщение боту
     *  (registerActivity), поэтому разница с createdAt - надёжный прокси "на какой день человек зашёл
     *  в последний раз", даже без отдельного лога визитов. */
    @Query("SELECT u.createdAt, u.lastActivityDate FROM AppUser u WHERE u.registrationCompleted = true AND u.createdAt <= :maxCreatedAt")
    List<Object[]> findCreatedAtAndLastActivityForChurn(@Param("maxCreatedAt") LocalDateTime maxCreatedAt);

    /** Перекрёстная проверка "отряды vs рефералка" (ТЗ EGC_TZ_otryady, рекомендация после Этапа 1, 2026-10-01):
     *  разрыв в удержании у "в отряде" может быть тенью уже известного эффекта "реферальный трафик качественнее
     *  рекламного" (самоотбор), а не самостоятельным эффектом отрядов. referredByTelegramId - отдельное поле от
     *  trafficSourceCode (см. UserService.getEngagementReport), это именно приглашение другом, а не источник закупа. */
    @Query("SELECT COUNT(u) FROM AppUser u WHERE u.registrationCompleted = true "
            + "AND ((:inSquad = true AND u.squadId IS NOT NULL) OR (:inSquad = false AND u.squadId IS NULL)) "
            + "AND ((:referred = true AND u.referredByTelegramId IS NOT NULL) OR (:referred = false AND u.referredByTelegramId IS NULL))")
    long countBySquadAndReferral(@Param("inSquad") boolean inSquad, @Param("referred") boolean referred);

    /** id активных с момента (та же трактовка активности, что в DAU/MAU) — для когортной аналитики пачек. */
    @Query("SELECT u.id FROM AppUser u WHERE u.lastActivityDate >= :sinceDate OR u.lastBotActivityAt >= :sinceDateTime OR u.lastMiniAppOpenAt >= :sinceDateTime")
    List<Long> findActiveUserIdsSince(@Param("sinceDate") java.time.LocalDate sinceDate, @Param("sinceDateTime") LocalDateTime sinceDateTime);

    @Query("SELECT COUNT(DISTINCT u) FROM AppUser u WHERE (u.lastActivityDate >= :sinceDate OR u.lastBotActivityAt >= :sinceDateTime OR u.lastMiniAppOpenAt >= :sinceDateTime) "
            + "AND ((:inSquad = true AND u.squadId IS NOT NULL) OR (:inSquad = false AND u.squadId IS NULL)) "
            + "AND ((:referred = true AND u.referredByTelegramId IS NOT NULL) OR (:referred = false AND u.referredByTelegramId IS NULL))")
    long countDistinctActiveSinceBySquadAndReferral(@Param("sinceDate") java.time.LocalDate sinceDate, @Param("sinceDateTime") LocalDateTime sinceDateTime,
                                                     @Param("inSquad") boolean inSquad, @Param("referred") boolean referred);

    /** Воронка "сколько квестов реально сделал новый игрок" — для диагностики, отваливаются ли
     *  новички после первого квеста (проблема вовлечения) или ещё раньше (проблема первого опыта). */
    @Query("SELECT COUNT(u) FROM AppUser u WHERE u.registrationCompleted = true AND u.createdAt >= :from AND u.createdAt < :to AND u.completedQuests >= :minQuests AND u.completedQuests <= :maxQuests")
    long countRegisteredBetweenWithCompletedQuestsBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to, @Param("minQuests") int minQuests, @Param("maxQuests") int maxQuests);

    /** Для разбивки "0 квестов" на "не вернулись вовсе" vs "вернулись, но квест не взяли" —
     *  сравнение lastBotActivityAt/createdAt по конкретным строкам делается в Java (UserService),
     *  дата-функции в JPQL не переносимы между H2/прод БД. */
    @Query("SELECT u FROM AppUser u WHERE u.registrationCompleted = true AND u.createdAt >= :from AND u.createdAt < :to AND u.completedQuests = :completedQuests")
    List<AppUser> findRegisteredBetweenWithCompletedQuests(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to, @Param("completedQuests") int completedQuests);

    List<AppUser> findAllBySquadId(Long squadId);

    long countBySquadId(Long squadId);

    /** Топ стран по числу игроков — для статистики под рекламодателя. country — свободный текст
     *  (self-reported через profile:edit_country), возможны дубли из-за разного написания/регистра. */
    @Query("SELECT u.country, COUNT(u) FROM AppUser u WHERE u.registrationCompleted = true AND u.country IS NOT NULL AND u.country <> '' GROUP BY u.country ORDER BY COUNT(u) DESC")
    List<Object[]> countUsersByCountry();

    @Query("SELECT COUNT(u) FROM AppUser u WHERE u.registrationCompleted = true AND u.createdAt >= :since AND u.referredByTelegramId IS NOT NULL")
    long countReferredNewUsersSince(@Param("since") LocalDateTime since);

    // ── Экономика рефералки (2026-09-09) — для расчёта разового бонуса рефереру vs текущие 10% ──
    @Query("SELECT COUNT(u) FROM AppUser u WHERE u.registrationCompleted = true AND u.referredByTelegramId IS NOT NULL")
    long countAllReferredUsers();

    @Query("SELECT COUNT(u) FROM AppUser u WHERE u.registrationCompleted = true AND u.referredByTelegramId IS NOT NULL AND u.completedQuests >= 1")
    long countReferredUsersWithAtLeastOneQuest();

    @Query("SELECT COALESCE(AVG(u.completedQuests), 0) FROM AppUser u WHERE u.registrationCompleted = true AND u.referredByTelegramId IS NOT NULL")
    double avgCompletedQuestsAmongReferred();
    // Суммы по referralEarnedExc сознательно НЕ используем для этого отчёта — поле смешивает
    // разовый инстант-бонус "+300 EXC за приглашение" и еженедельный "10% с квеста реферала" под
    // одним и тем же полем/типом транзакции. См. ExcTransactionRepository.sumReferralTrickleOnly —
    // там они разделены по тексту заметки.

    Optional<AppUser> findByPhoneNumberAndTelegramIdNot(String phoneNumber, Long excludeTelegramId);

    /** Серия новичку «возьми первый квест»: идёт, пока человек не взял ни одного квеста (а не пока не нажал кнопку онбординга),
     * только в окне после старта (since) - накопленная до деплоя база не получит залп. Подписку на канал не прошедшие
     * органические игроки тоже в списке: им уйдёт напоминание про подписку (заявки трафиковых разбирает админ). */
    @Query("SELECT u FROM AppUser u WHERE u.profileCompleted = true AND u.onboardingStartedAt IS NOT NULL AND u.onboardingStartedAt > :since "
            + "AND u.onboardingNotificationsSent < 3 AND u.blocked = false AND u.lastQuestTakenAt IS NULL AND u.completedQuests = 0 "
            + "AND (u.registrationCompleted = true OR u.trafficSourceCode IS NULL)")
    List<AppUser> findUsersAwaitingFirstQuest(@Param("since") java.time.LocalDateTime since);

    /** Приглашённые, зарегистрировавшиеся в окне [from, to] и не выполнившие первый квест, пригласившему о них ещё не напоминали (напоминание «твой друг не начал»). */
    @Query("SELECT u FROM AppUser u WHERE u.referredByTelegramId IS NOT NULL AND u.completedQuests = 0 AND u.referrerNudgeSentAt IS NULL "
            + "AND u.blocked = false AND u.createdAt >= :from AND u.createdAt <= :to ORDER BY u.createdAt ASC")
    List<AppUser> findStalledReferredFriends(@Param("from") java.time.LocalDateTime from, @Param("to") java.time.LocalDateTime to,
                                             org.springframework.data.domain.Pageable pageable);

    List<AppUser> findAllByReferredByTelegramIdIsNotNullAndReferralActiveTrue();

    List<AppUser> findAllByReferredByTelegramId(Long telegramId);

    @Query("SELECT u FROM AppUser u WHERE u.clashOfClansTag IS NOT NULL OR u.clashRoyaleTag IS NOT NULL ORDER BY u.telegramId")
    List<AppUser> findAllWithClashTags();

    /** Все игроки, пришедшие по метке закупа, — сырьё для сравнения источников (TrafficFunnelService):
     *  код, id, дата регистрации, флаги профиля/активации и три поля активности (как в MAU/DAU). */
    @org.springframework.data.jpa.repository.Query("SELECT u.trafficSourceCode, u.id, u.createdAt, u.profileCompleted, u.registrationCompleted, "
            + "u.lastActivityDate, u.lastBotActivityAt, u.lastMiniAppOpenAt FROM AppUser u WHERE u.trafficSourceCode IS NOT NULL")
    List<Object[]> findTrafficSourceUsersForFunnel();

    long countByBlockedTrue();

    long countByReferredByTelegramIdIsNotNull();

    long countByReferredByTelegramIdIsNotNullAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(LocalDateTime from, LocalDateTime to);

    long countByTrafficSourceCodeIsNotNullAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(LocalDateTime from, LocalDateTime to);

    /** Регистрации за период для выгрузки в CSV (границы: from включительно, to исключительно). */
    List<AppUser> findAllByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(LocalDateTime from, LocalDateTime to);
}
