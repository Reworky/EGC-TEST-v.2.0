package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.Vpn2Account;

public interface Vpn2AccountRepository extends JpaRepository<Vpn2Account, Long> {

    Optional<Vpn2Account> findByTelegramId(Long telegramId);

    /** Для напоминаний о сроке: аккаунты в статусе, у которых срок попадает в окно и очередное напоминание ещё не слали. */
    List<Vpn2Account> findByStatusAndReminderStageLessThanAndExpiresAtBetween(String status, int stage, LocalDateTime from, LocalDateTime to);

    /** Опрос в конце пробного: пробные, срок кончается в окне, оценку ещё не спрашивали. */
    List<Vpn2Account> findByPlanTypeAndStatusAndFeedbackStageLessThanAndExpiresAtBetween(String planType, String status, int stage, LocalDateTime from, LocalDateTime to);

    /** Опрос платного через неделю: платные, созданные раньше границы, оценку ещё не спрашивали (работает ли ключ сейчас, проверяет вызывающий). */
    List<Vpn2Account> findByPlanTypeAndStatusAndFeedbackStageLessThanAndCreatedAtBefore(String planType, String status, int stage, LocalDateTime before);

    /** Опрос «почему не продлили»: срок истёк в окне, этот опрос ещё не слали. */
    List<Vpn2Account> findByStatusAndFeedbackStageLessThanAndExpiresAtBetween(String status, int stage, LocalDateTime from, LocalDateTime to);

    /** Пробные, которые работают сейчас и о лимите трафика которым ещё не сообщали. */
    List<Vpn2Account> findByPlanTypeAndStatusAndLimitNotifiedAtIsNullAndExpiresAtAfter(String planType, String status, LocalDateTime after);

    /** Ключи, выданные в окне, по которым ещё не проверяли, подключился ли игрок. */
    List<Vpn2Account> findByStatusAndHelpNudgedAtIsNullAndCreatedAtBetween(String status, LocalDateTime from, LocalDateTime to);

    long countByStatusAndPlanTypeAndExpiresAtAfter(String status, String planType, LocalDateTime after);

    long countByStatusAndExpiresAtBetween(String status, LocalDateTime from, LocalDateTime to);

    long countByTrialUsedAtIsNotNull();
}
