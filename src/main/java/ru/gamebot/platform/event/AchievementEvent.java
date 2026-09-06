package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;
import ru.gamebot.platform.domain.enums.AchievementType;

/** Пользователь разблокировал достижение (победа в турнире / новый уровень / круглая сумма EXC) —
 *  боту нужно прислать карточку-достижение с кнопкой «Поделиться» (см. GamePlatformBot.onAchievement). */
public class AchievementEvent extends ApplicationEvent {

    private final Long telegramId;
    private final AchievementType type;
    /** Название турнира / новое звание / сумма майлстоуна — конкретный смысл зависит от type. */
    private final String detail;

    public AchievementEvent(Object source, Long telegramId, AchievementType type, String detail) {
        super(source);
        this.telegramId = telegramId;
        this.type = type;
        this.detail = detail;
    }

    public Long getTelegramId() { return telegramId; }
    public AchievementType getType() { return type; }
    public String getDetail() { return detail; }
}
