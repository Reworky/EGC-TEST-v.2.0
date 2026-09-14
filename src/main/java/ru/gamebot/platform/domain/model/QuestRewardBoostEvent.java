package ru.gamebot.platform.domain.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Глобальное временное окно, умножающее награду EXC за одобренный квест для ВСЕХ игроков сразу —
 *  не путать с ReferralBoostEvent (тот умножает только разовый реферальный бонус) и с личным
 *  AppUser.excBoostActiveUntil (тот — купленный в магазине персональный буст одного игрока).
 *  Складывается с личным бустом аддитивно (см. QuestService.computeReward), не заменяет его. */
@Getter
@Setter
@Entity
@Table(name = "quest_reward_boost_events")
public class QuestRewardBoostEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime startAt;
    private LocalDateTime endAt;

    /** В процентах сверху базовой награды — 100 = ×2. */
    private int boostPercent;

    private boolean active;

    private LocalDateTime createdAt;
}
