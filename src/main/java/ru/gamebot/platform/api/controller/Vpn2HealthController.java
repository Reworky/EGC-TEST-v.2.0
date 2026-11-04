package ru.gamebot.platform.api.controller;

import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.gamebot.platform.service.Vpn2HealthService;

/** Приём сигналов от скрипта проверки EGC VPN на сервере входа: heartbeat и ежедневная копия базы панели. Доступ только по секрету в заголовке X-Health-Key
 *  (VPN2_HEALTH_SECRET); без секрета в настройках или с неверным - 403 без подробностей. */
@Slf4j
@RestController
@RequestMapping("/api/public/vpn2")
@RequiredArgsConstructor
public class Vpn2HealthController {

    private final Vpn2HealthService health;

    @PostMapping(value = "/heartbeat", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> heartbeat(@RequestHeader(value = "X-Health-Key", required = false) String key,
                                          @RequestBody Map<String, Object> body) {
        if (!health.keyOk(key)) {
            return ResponseEntity.status(403).build();
        }
        health.onHeartbeat(body);
        return ResponseEntity.ok().build();
    }

    @PostMapping(value = "/panel-backup", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<String> panelBackup(@RequestHeader(value = "X-Health-Key", required = false) String key,
                                              @RequestBody byte[] data) {
        if (!health.keyOk(key)) {
            return ResponseEntity.status(403).build();
        }
        try {
            return ResponseEntity.ok(health.saveBackup(data));
        } catch (IOException e) {
            log.warn("VPN2 panel backup rejected: {}", e.getMessage());
            return ResponseEntity.badRequest().body("rejected");
        }
    }
}
