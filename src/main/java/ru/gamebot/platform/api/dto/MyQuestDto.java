package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MyQuestDto {
    private Long submissionId;
    private Long questId;
    private String title;
    private String gameName;
    private String category;
    private boolean externalAutoApprove;
    /** Спонсорский или внешний квест: в «Мои квесты» мини-аппа и бота они в отдельном списке «Мои спонсорские», без слотов. */
    private boolean sponsoredKind;
    /** Рубли от банка по партнёрской миссии (0 - нет): на карточке вместо «0 EXC». */
    private long bankRewardRub;
    private boolean brawlAutoVerify;

    /** Только для авто-верифицируемых квестов. null = ещё не авто-квест, либо (если target тоже null)
     *  идёт первый замер после взятия — baseline ещё не зафиксирован API. */
    private Integer autoVerifyProgress;
    private Integer autoVerifyTarget;

    private String status;
    private String updatedAt;
    private String expiresAt;
    private String moderatorComment;
    private long rewardXp;
    private long rewardCoins;
    private boolean sponsorReward;
}
