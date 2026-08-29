package ru.gamebot.platform.api.dto;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ReferralRankingDto {
    private List<Entry> top;
    /** Отдельная строка для текущего пользователя, если он вне top (null, если уже входит в top). */
    private Entry yourEntry;

    @Data
    @Builder
    public static class Entry {
        private int rank;
        private String nickname;
        private int invitedFriends;
        private long weeklyExc;
        private boolean isMe;
    }
}
