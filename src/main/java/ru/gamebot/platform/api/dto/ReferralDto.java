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
}
