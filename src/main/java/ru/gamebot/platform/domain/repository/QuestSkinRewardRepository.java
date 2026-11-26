package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.gamebot.platform.domain.model.QuestSkinReward;

public interface QuestSkinRewardRepository extends JpaRepository<QuestSkinReward, Long> {

    Optional<QuestSkinReward> findByRewardKey(String rewardKey);

    List<QuestSkinReward> findAllByTelegramIdAndStatus(Long telegramId, String status);

    List<QuestSkinReward> findAllByStatus(String status);

    /** Атомарный перевод статуса: 1 - перевели мы, 0 - уже кто-то другой (защита от двойной покупки и двойной выплаты). */
    @Modifying
    @Query("update QuestSkinReward r set r.status = :to, r.updatedAt = :now where r.id = :id and r.status = :from")
    int moveStatus(@Param("id") Long id, @Param("from") String from, @Param("to") String to, @Param("now") LocalDateTime now);
}
