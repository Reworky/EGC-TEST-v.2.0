package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ReferralDto {
    private String referralLink;
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
}
