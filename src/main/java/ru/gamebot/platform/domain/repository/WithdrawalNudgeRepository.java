package ru.gamebot.platform.domain.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.gamebot.platform.domain.model.WithdrawalNudge;

public interface WithdrawalNudgeRepository extends JpaRepository<WithdrawalNudge, Long> {

    /** Кому «вывод открыт», но заявки на вывод не было и игрок заходил за последние 7 дней (то же условие, что в scripts/audit-limits/14). */
    @Query(value = "SELECT u.id FROM app_users u "
            + "WHERE u.registration_completed = TRUE AND u.blocked = FALSE AND u.withdrawal_frozen = FALSE AND u.fraud_suspect = FALSE "
            + "AND u.coins >= 5000 AND u.completed_quests >= 3 AND u.created_at <= DATEADD('DAY', -3, CURRENT_TIMESTAMP) "
            + "AND (CASE WHEN u.last_bot_activity_at IS NOT NULL AND (u.last_activity_date IS NULL OR CAST(u.last_bot_activity_at AS DATE) > u.last_activity_date) "
            + "THEN CAST(u.last_bot_activity_at AS DATE) ELSE COALESCE(u.last_activity_date, CAST(u.created_at AS DATE)) END) >= DATEADD('DAY', -7, CURRENT_DATE) "
            + "AND NOT EXISTS (SELECT 1 FROM reward_requests rr JOIN reward_items ri ON ri.id = rr.reward_id WHERE rr.user_id = u.id AND ri.category = 'Вывод') "
            + "ORDER BY u.id", nativeQuery = true)
    List<Long> findEligibleUserIds();

    List<WithdrawalNudge> findBySentAtIsNullAndGrp(String grp);

    /** По группам: назначено, отправлено, подали заявку на вывод после назначения, заходили после назначения. */
    @Query(value = "SELECT n.grp, COUNT(*), SUM(CASE WHEN n.sent_at IS NOT NULL THEN 1 ELSE 0 END), "
            + "SUM(CASE WHEN EXISTS (SELECT 1 FROM reward_requests rr JOIN reward_items ri ON ri.id = rr.reward_id "
            + "WHERE rr.user_id = n.id AND ri.category = 'Вывод' AND rr.created_at > n.assigned_at) THEN 1 ELSE 0 END), "
            + "SUM(CASE WHEN (CASE WHEN u.last_bot_activity_at IS NOT NULL AND (u.last_activity_date IS NULL OR CAST(u.last_bot_activity_at AS DATE) > u.last_activity_date) "
            + "THEN CAST(u.last_bot_activity_at AS DATE) ELSE COALESCE(u.last_activity_date, CAST(u.created_at AS DATE)) END) > CAST(n.assigned_at AS DATE) THEN 1 ELSE 0 END) "
            + "FROM withdrawal_nudges n JOIN app_users u ON u.id = n.id GROUP BY n.grp ORDER BY n.grp", nativeQuery = true)
    List<Object[]> statsByGroup();
}
