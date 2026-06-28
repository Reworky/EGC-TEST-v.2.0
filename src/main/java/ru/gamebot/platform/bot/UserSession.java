package ru.gamebot.platform.bot;

import java.util.HashMap;
import java.util.Map;

public class UserSession {

    private SessionState state = SessionState.NONE;
    private Long questId;
    private Long rewardId;
    private Long submissionId;
    private Long supportTicketId;
    private final Map<String, String> data = new HashMap<>();

    public SessionState getState() { return state; }
    public void setState(SessionState state) { this.state = state; }

    public Long getQuestId() { return questId; }
    public void setQuestId(Long questId) { this.questId = questId; }

    public Long getRewardId() { return rewardId; }
    public void setRewardId(Long rewardId) { this.rewardId = rewardId; }

    public Long getSubmissionId() { return submissionId; }
    public void setSubmissionId(Long submissionId) { this.submissionId = submissionId; }

    public Long getSupportTicketId() { return supportTicketId; }
    public void setSupportTicketId(Long supportTicketId) { this.supportTicketId = supportTicketId; }

    public Map<String, String> getData() { return data; }

    public void reset() {
        state = SessionState.NONE;
        questId = null;
        rewardId = null;
        submissionId = null;
        supportTicketId = null;
        data.clear();
    }
}
