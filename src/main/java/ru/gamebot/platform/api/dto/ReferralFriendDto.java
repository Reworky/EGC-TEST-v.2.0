package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

/** Приглашённый друг в списке «Рефералы» профиля на сайте. */
@Data
@Builder
public class ReferralFriendDto {
    private String nickname;
    /** Дата вступления в клуб (dd.MM.yyyy HH:mm), null у старых записей без даты. */
    private String joinedAt;
    private int completedQuests;
}
