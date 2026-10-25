package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ReferralDto {
    private String referralLink;
    private String shareUrl;
    private int invitedFriends;
    private long earnedExc;
    private long nextMilestone;
    private int progressPercent;
    private boolean boostActive;
    private Integer boostMultiplier;
    private String boostEndsAt;
    private String currentFriendBadge;
    private Integer nextFriendMilestone;
    private int friendProgressPercent;
    /** Цель «позови 1 друга» открыта: награда за первого друга, выполнившего первый квест, ещё не выдана. */
    private boolean goalOpen;
    /** Друг уже в клубе (invitedFriends > 0), ждём его 3 квеста и 3 дня. */
    private boolean goalFriendJoined;
}
