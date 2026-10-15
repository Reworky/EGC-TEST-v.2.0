package ru.gamebot.platform.api.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.gamebot.platform.service.ProofStatsService;

/** Публичные цифры для страницы «Как EGC проверяет результат» (egc-club.online/partners/proof). Только агрегаты, без персональных данных;
 *  значения пересчитываются не чаще раза в час. Доля = null, если выборка меньше 30 («мало данных»). */
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/public/proof")
@RequiredArgsConstructor
public class PublicProofController {

    private final ProofStatsService proofStatsService;

    @GetMapping
    public Map<String, Object> proof() {
        ProofStatsService.Stats s = proofStatsService.cachedStats();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("collectedAt", s.collectedAt().toLocalDate().toString());
        out.put("windowDays", s.windowDays());
        out.put("playersTotal", roundDown(s.playersTotal(), 10));
        out.put("activeWeek", s.activeWeek());
        out.put("playersApproved", s.playersApproved());
        out.put("decided", s.decided());
        out.put("approvedPercent", s.approvedPercent());
        out.put("autoApproved", s.autoApproved());
        out.put("approved", s.approved());
        out.put("autoApprovedPercent", s.autoApprovedPercent());
        out.put("manualDecided", s.manualDecided());
        out.put("rejectedManualPercent", s.rejectedManualPercent());
        out.put("submitted", s.submitted());
        out.put("duplicatePercent", s.duplicatePercent());
        out.put("retention7Percent", s.retention7Percent());
        out.put("retentionCohort7", s.retentionCohort7());
        out.put("retention30Percent", s.retention30Percent());
        out.put("retentionCohort30", s.retentionCohort30());
        return out;
    }

    private static long roundDown(long v, long step) {
        return v / step * step;
    }
}
