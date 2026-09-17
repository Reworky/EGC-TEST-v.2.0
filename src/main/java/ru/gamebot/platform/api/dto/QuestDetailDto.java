package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class QuestDetailDto {
    private Long id;
    private String title;
    private String description;
    private String instruction;
    private String requirements;
    private String gameName;
    private String category;
    private String platform;
    private int durationDays;
    private long rewardXp;
    private long rewardCoins;
    private int ticketReward;
    private boolean councilOnly;

    /** Квест без кулдауна и лимита участников (пилот "квесты без стен") — после APPROVED фронтенд должен
     *  сразу предлагать взять квест снова, а не показывать тупиковое "выполнен и оплачен" как для обычных
     *  квестов, где нужно ждать кулдаун. */
    private boolean repeatableNoCooldownEligible;

    /** Внешний авто-квест (партнёрская сеть): вместо отчёта нужно просто перейти по ссылке из instruction. */
    private boolean externalAutoApprove;

    /** Квест-веха (скриншот подтверждает текущее состояние аккаунта, напр. "достигни ТХ10") — после
     *  APPROVED пересдать нельзя никогда, в отличие от обычных повторяемых квестов с кулдауном. Фронтенд
     *  использует это, чтобы не предлагать "Пройти ещё раз" там, где повтор в принципе невозможен. */
    private boolean oneTimePerAccount;

    /** Название сохранено для обратной совместимости с фронтендом — на деле покрывает авто-верификацию
     *  по API любой из трёх игр (Brawl Stars/Clash of Clans/Clash Royale), не только Brawl. Прогресс
     *  отслеживается автоматически, отчёт не нужен. */
    private boolean brawlAutoVerify;

    /** Только для авто-верифицируемых квестов с активной заявкой. null = ещё не авто-квест, либо
     *  (если target тоже null) идёт первый замер после взятия — baseline ещё не зафиксирован API. */
    private Integer autoVerifyProgress;
    private Integer autoVerifyTarget;

    /** null, если пользователь ещё не брал этот квест */
    private String submissionStatus;
    private String moderatorComment;
    private String expiresAt;
}
