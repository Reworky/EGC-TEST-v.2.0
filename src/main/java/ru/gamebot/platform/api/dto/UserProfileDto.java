package ru.gamebot.platform.api.dto;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserProfileDto {
    private Long telegramId;
    private String nickname;
    private String country;
    private String platformsCsv;
    private String interestsCsv;
    private String profileTitle;
    /** Куплен ли эксклюзивный Stars-титул «Покровитель EGC» (ownedTitlesCsv содержит "patron") —
     * чтобы мини-апп мог скрыть карточку покупки после оплаты, не дожидаясь смены profileTitle
     * (владелец может позже переключиться на обычный EXC-титул, не потеряв статус покупки). */
    private boolean hasPatronTitle;
    /** Куплен ли доп. слот квеста навсегда за Stars (permanentExtraSlot) — чтобы мини-апп мог
     * скрыть карточку покупки после оплаты. */
    private boolean hasPermanentExtraSlot;
    /** Активна ли подписка EGC Pass прямо сейчас (egcPassActiveUntil в будущем). */
    private boolean hasEgcPass;
    /** Дата, до которой действует EGC Pass (dd.MM.yyyy), null если подписки нет/истекла. */
    private String egcPassActiveUntil;
    /** Сколько EXC бонуса подписки (+10% за квесты) уже начислено в текущем месяце и месячный потолок —
     *  для строки статуса подписки в профиле. Для не-подписчика оба 0. */
    private long egcPassBoostUsedThisMonth;
    private long egcPassBoostMonthlyCap;
    private long xp;
    private long coins;
    private int level;
    private String levelName;
    private int completedQuests;
    private int streakDays;
    private long monthlyWithdrawalLimit;
    private long remainingWithdrawalLimit;
    private boolean hasAvatar;
    /** Купленный в магазине цвет рамки аватара (hex). null = цвет по умолчанию (по уровню). */
    private String avatarFrameColor;
    /** Ключ картинки рамки аватара (например "fire"), купленной в магазине. null = обычная цветная обводка. */
    private String avatarFrameImage;
    /** Список ключей всех рамок, которыми владеет пользователь (["fire", "ice", "gold"]). */
    private List<String> ownedFrames;
    private int invitedFriends;
    /** Название бейджа за число приглашённых друзей (Модуль 3 максимизации рефералки). null = ещё нет бейджа. */
    private String friendBadgeName;
    /** Состоит ли в отряде прямо сейчас — сектор "Отряд" в гербе достижений на профиле. */
    private boolean inSquad;
    /** Сколько ещё одобренных квестов до конца новичкового темпа (короче кулдауны/лимиты).
     * 0 = новичковый темп уже закончился, обычные лимиты. */
    private int onboardingQuestsLeft;
}
