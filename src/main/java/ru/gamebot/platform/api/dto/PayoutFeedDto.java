package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Лента выплат для мини-аппа: последние выплаты (ники замаскированы) и итоги за 24 часа и 7 дней. */
@Data
@Builder
public class PayoutFeedDto {
    private List<Item> items;
    private long dayRub;
    private long dayCount;
    private long weekRub;
    private long weekCount;
    private String reviewsUrl;

    @Data
    @Builder
    public static class Item {
        private long rub;
        /** Сумма в «родной» единице способа: «1 200 ₽» (СБП), «1 000 ⭐» (Stars), «~12.5 GRAM» (крипто). */
        private String amountText;
        /** stars | ton | sbp */
        private String method;
        private long minutesAgo;
        private String name;
    }
}
