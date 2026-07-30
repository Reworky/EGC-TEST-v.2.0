package ru.gamebot.platform.service;

public record AiVerificationResult(
        String decision,      // "APPROVE", "REJECT", "MANUAL"
        double confidence,    // 0.0 – 1.0
        String reason,        // human-readable explanation
        String checksJson     // raw JSON with individual check results
) {
    public boolean isApprove() { return "APPROVE".equals(decision); }
    public boolean isReject()  { return "REJECT".equals(decision);  }
    public boolean isManual()  { return "MANUAL".equals(decision);  }
}
