package ru.gamebot.platform.service;

/** Виды напоминаний, на которые действует лимит частоты (NotificationGateService). Ответы на действия игрока (награда за
 * квест, автозачёт, приз, оплата, достижение, ответ модерации) сюда НЕ входят и лимитом не блокируются.
 * priority: при нескольких кандидатах в одном окне проходит более важный (дедлайн > серия > кулдаун > второй квест > ...). */
public enum NudgeType {
    QUEST_DEADLINE(100, true, "⏰ Дедлайн квеста"),
    STREAK_BROKEN(95, true, "💔 Серия прервалась"),
    STREAK_AT_RISK(90, false, "🔥 Серия под угрозой"),
    COOLDOWN_EXPIRED(80, false, "🎮 Кулдаун снят"),
    ONBOARDING(75, false, "👋 Онбординг"),
    SECOND_QUEST(70, false, "🎁 Второй квест"),
    DORMANCY(60, false, "💤 Возвращение (14/30/60 дн.)"),
    QUEST_GAP(50, false, "👀 Квесты не берёшь"),
    COOLDOWN_REMINDER(45, false, "⏰ Повтор про кулдаун"),
    SILENT_GAP(40, false, "📭 Затих на 4-13 дней"),
    WEEKLY_DIGEST(30, false, "📊 Недельный дайджест"),
    EGC_PASS_TEASER(20, false, "⭐ EGC Pass");

    private final int priority;
    private final boolean bypassLimit;
    private final String label;

    NudgeType(int priority, boolean bypassLimit, String label) {
        this.priority = priority;
        this.bypassLimit = bypassLimit;
        this.label = label;
    }

    public int getPriority() { return priority; }
    /** true - идёт сверх лимита (иначе игрок потеряет заявку), но всё равно пишется в журнал. */
    public boolean isBypassLimit() { return bypassLimit; }
    public String getLabel() { return label; }
}
