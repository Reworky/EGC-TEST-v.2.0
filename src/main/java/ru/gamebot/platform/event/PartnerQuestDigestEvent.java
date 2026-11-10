package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;
import ru.gamebot.platform.service.PartnerQuestNudgeService;

/** Ежедневная сводка менеджерам по партнёрскому квесту (11:00 по Москве): кто ждёт ответа и что с выплатой банка. */
public class PartnerQuestDigestEvent extends ApplicationEvent {

    private final PartnerQuestNudgeService.Digest digest;

    public PartnerQuestDigestEvent(Object source, PartnerQuestNudgeService.Digest digest) {
        super(source);
        this.digest = digest;
    }

    public PartnerQuestNudgeService.Digest getDigest() { return digest; }
}
