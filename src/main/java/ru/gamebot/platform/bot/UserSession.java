package ru.gamebot.platform.bot;

import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserSession {

    private SessionState state = SessionState.NONE;
    private Long questId;
    private Long rewardId;
    private Long submissionId;
    private final Map<String, String> data = new HashMap<>();

    public void reset() {
        state = SessionState.NONE;
        questId = null;
        rewardId = null;
        submissionId = null;
        data.clear();
    }
}
